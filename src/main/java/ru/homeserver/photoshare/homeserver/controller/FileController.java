package ru.homeserver.photoshare.homeserver.controller;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import ru.homeserver.photoshare.homeserver.dto.*;
import ru.homeserver.photoshare.homeserver.service.FileService;
import ru.homeserver.photoshare.homeserver.service.FolderPrepareService;
import ru.homeserver.photoshare.homeserver.service.MetadataService;
import ru.homeserver.photoshare.homeserver.service.ThumbnailService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/*
 * @RestController = @Controller + @ResponseBody
 *
 * Это значит:
 * - методы этого класса принимают HTTP-запросы
 * - возвращаемые объекты будут автоматически превращены в JSON
 *   (если это не Resource / ResponseEntity<Resource>)
 */
@RestController

/*
 * Базовый путь для всех методов контроллера.
 * То есть все методы начинаются с /api/files
 */
@RequestMapping("/api/files")
public class FileController {
    private final Map<String, Object> uploadLocks = new ConcurrentHashMap<>();
    private final FileService fileService;
    private final ThumbnailService thumbnailService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private FolderPrepareService folderPrepareService;

    private final String ffmpegPath;
    private final String ffprobePath;
    private final AppProperties appProperties;
    private final Map<String, Process> previewProcesses = new ConcurrentHashMap<>();
    private final Map<String, Integer> previewProgress = new ConcurrentHashMap<>();
    private final Map<String, Path> previewFiles = new ConcurrentHashMap<>();
    private final MetadataService metadataService;
    public FileController(
            FileService fileService,
            ThumbnailService thumbnailService,
            AppProperties appProperties,
            @Value("${app.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${app.ffprobe-path:ffprobe}") String ffprobePath,
            MetadataService metadataService
    ) {
        this.fileService = fileService;
        this.thumbnailService = thumbnailService;
        this.appProperties = appProperties;
        this.ffmpegPath = ffmpegPath;
        this.ffprobePath = ffprobePath;
        this.metadataService = metadataService;
    }
    private Path getUploadTempDir() throws IOException {
        Path dir = Path.of(appProperties.getPreviewCacheDir()).resolve("upload_tmp");
        Files.createDirectories(dir);
        return dir;
    }
    private Path getMetaFile(String uploadId) throws IOException {
        return getUploadTempDir().resolve(uploadId + ".meta.json");
    }

    private Path getTempFile(String uploadId) throws IOException {
        return getUploadTempDir().resolve(uploadId + ".tmp");
    }

    private UploadSessionDto readMeta(String uploadId) throws IOException {
        Path metaFile = getMetaFile(uploadId);
        if (!Files.exists(metaFile)) {
            return null;
        }
        return objectMapper.readValue(metaFile.toFile(), UploadSessionDto.class);
    }

    private void writeMeta(UploadSessionDto meta) throws IOException {
        Path metaFile = getMetaFile(meta.getUploadId());
        objectMapper.writeValue(metaFile.toFile(), meta);
    }
    @PostMapping("/preview/start")
    public ResponseEntity<?> startPreview(@RequestParam String path) throws IOException {
        Path source = fileService.resolveSafe(path);

        if (!Files.exists(source) || Files.isDirectory(source)) {
            return ResponseEntity.notFound().build();
        }

        String name = source.getFileName().toString().toLowerCase();

        if (!name.endsWith(".lrv") && !name.endsWith(".insv")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only LRV/INSV supported"));
        }

        String previewId = UUID.randomUUID().toString();
        Path tempDir = Path.of(appProperties.getPreviewCacheDir());

        Files.createDirectories(tempDir);

        Path output = tempDir.resolve(previewId + ".preview.mp4");

        previewProgress.put(previewId, 0);
        previewFiles.put(previewId, output);

        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        ffmpegPath,
                        "-y",

                        "-i", source.toAbsolutePath().toString(),

                        "-map", "0:v:0",
                        "-map", "0:a:0?",

                        "-c", "copy",

                        "-movflags", "+faststart",

                        output.toAbsolutePath().toString()
                );
                pb.redirectErrorStream(true);

                Process process = pb.start();
                previewProcesses.put(previewId, process);

                try (InputStream is = process.getInputStream()) {
                    is.transferTo(OutputStream.nullOutputStream());
                }

                int exitCode = process.waitFor();

                if (exitCode == 0 && Files.exists(output) && Files.size(output) > 0) {
                    previewProgress.put(previewId, 100);
                } else {
                    Files.deleteIfExists(output);
                    previewProgress.put(previewId, -1);
                }

            } catch (Exception e) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                }
                previewProgress.put(previewId, -1);
            } finally {
                previewProcesses.remove(previewId);
            }
        }).start();

        return ResponseEntity.ok(Map.of("previewId", previewId));
    }
    @GetMapping("/preview/original")
    public ResponseEntity<Resource> downloadOriginal(@RequestParam String previewId) throws IOException {
        Path renamed = previewFiles.get(previewId);
        RenamePreviewSessionDto session = readPreviewJournal(getPreviewJournalFile(previewId));

        if (renamed == null || !Files.exists(renamed)) {
            return ResponseEntity.notFound().build();
        }

        // восстанавливаем оригинальное имя (убираем .preview.mp4)
        String fileName = renamed.getFileName().toString();
        /*String originalName = fileName.replaceAll("\\.preview\\.mp4$", ".insv");*/ // или .lrv при необходимости
        String originalName = Path.of(session.getOriginalPath()).getFileName().toString();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + originalName + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(renamed));
    }
   @PostMapping("/prepare-folder")
   public Map<String, String> prepareFolder(
           @RequestParam String path,
           @RequestParam(defaultValue = "name") String sortField,
           @RequestParam(defaultValue = "asc") String sortDirection
   ) {
       String jobId = folderPrepareService.start(path, sortField, sortDirection);
       return Map.of("jobId", jobId);
   }
    @GetMapping("/prepare-status")
    public Map<String, Object> prepareStatus(@RequestParam String jobId) {
        FolderPrepareJob job = folderPrepareService.get(jobId);

        if (job == null) {
            return Map.of("error", "not_found");
        }
        return Map.of(
                "ready", job.ready,
                "progress", job.progress,
                "processed", job.processed,
                "total", job.total,
                "stage", job.stage,
                "itemsTotal", job.items != null ? job.items.size() : 0
        );
    }
    @GetMapping("/prepared-items")
    public Map<String, Object> preparedItems(
            @RequestParam String jobId,
            @RequestParam int offset,
            @RequestParam int limit
    ) {
        FolderPrepareJob job = folderPrepareService.get(jobId);

        if (job == null || !job.ready) {
            return Map.of("items", List.of());
        }

        int to = Math.min(offset + limit, job.items.size());

        return Map.of(
                "items", job.items.subList(offset, to),
                "total", job.items.size()
        );
    }
    @DeleteMapping("/preview/cancel")
    public ResponseEntity<?> cancelPreview(@RequestParam String previewId) {
        try {
            Process process = previewProcesses.remove(previewId);

            if (process != null) {
                process.destroyForcibly();

                try {
                    process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Path file = previewFiles.remove(previewId);

            Path journalFile = getPreviewJournalFile(previewId);

            if (Files.exists(journalFile)) {
                try {
                    RenamePreviewSessionDto session = readPreviewJournal(journalFile);

                    Path original = Path.of(session.getOriginalPath());
                    Path renamed = Path.of(session.getRenamedPath());

                    Thread.sleep(100);

                    if (Files.exists(renamed) && !Files.exists(original)) {
                        Files.move(renamed, original, StandardCopyOption.REPLACE_EXISTING);
                    }

                    session.setStatus("CLOSED");
                    writePreviewJournal(session);

                } catch (Exception e) {
                    System.out.println("Failed to rollback renamed preview: " + previewId);
                    e.printStackTrace();
                }
            } else if (file != null) {
                try {
                    Thread.sleep(100);
                    Files.deleteIfExists(file);
                } catch (Exception e) {
                    System.out.println("Failed to delete preview file: " + file);
                    e.printStackTrace();
                }
            }

            previewProgress.remove(previewId);

            return ResponseEntity.ok(Map.of("message", "Preview cancelled"));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.ok(Map.of("message", "Preview cancel ignored"));
        }
    }
    @GetMapping("/preview/file")
    public ResponseEntity<ResourceRegion> previewFile(
            @RequestParam String previewId,
            @RequestHeader HttpHeaders headers
    ) throws IOException {

        Path file = previewFiles.get(previewId);

        if (file == null || !Files.exists(file) || Files.size(file) == 0) {
            return ResponseEntity.notFound().build();
        }

        FileSystemResource resource = new FileSystemResource(file);
        long fileSize = resource.contentLength();

        // 👉 размер чанка (очень важно для мобильных)
        final long chunkSize = 8 * 1024 * 1024; // 8 MB

        List<HttpRange> ranges = headers.getRange();

        if (ranges.isEmpty()) {
            ResourceRegion region = new ResourceRegion(resource, 0, Math.min(chunkSize, fileSize));

            return ResponseEntity.status(HttpStatus.OK)
                    .contentType(MediaType.parseMediaType("video/mp4"))
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(region);
        }

        HttpRange range = ranges.get(0);

        long start = range.getRangeStart(fileSize);
        long end = range.getRangeEnd(fileSize);

        long rangeLength = Math.min(chunkSize, end - start + 1);

        ResourceRegion region = new ResourceRegion(resource, start, rangeLength);

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(region);
    }
    @GetMapping("/preview/status")
    public ResponseEntity<?> previewStatus(@RequestParam String previewId) throws IOException {
        Integer progress = previewProgress.get(previewId);
        Path file = previewFiles.get(previewId);

        if (progress == null || file == null) {
            return ResponseEntity.notFound().build();
        }

        boolean ready = progress == 100 && Files.exists(file) && Files.size(file) > 0;

        return ResponseEntity.ok(Map.of(
                "progress", progress,
                "ready", ready,
                "size", Files.exists(file) ? Files.size(file) : 0
        ));
    }

    /*
     * GET /api/files/list?path=...
     *
     * Возвращает список файлов в папке.
     */
    @GetMapping("/list")
    public Map<String, Object> list(
            @RequestParam(defaultValue = "") String path,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit
    ) throws IOException {
        /*
         * @RequestParam читает query parameter из URL.
         *
         * Например:
         * /api/files/list?path=photos/2026
         *
         * defaultValue = "" означает:
         * если параметр не передан, использовать пустую строку.
         */
        Path current = fileService.resolveSafe(path);
        long total = fileService.countItems(path);
        /*
         * normalized — текущий путь в удобном для фронтенда виде.
         * Если это rootPath, то возвращаем пустую строку.
         * parent — относительный путь к родительской папке.
         * Нужен кнопке "Назад".
         */
        String normalized = fileService.getRootPath().equals(current)
                ? ""
                : fileService.getRootPath()
                .relativize(current)
                .toString()
                .replace("\\", "/");
        /*String normalized = fileService.getRootPath().equals(current)
                ? ""
                : fileService.getRootPath().relativize(current).toString().replace("\\", "/");*/

                String parent = "";
        if (StringUtils.hasText(normalized)) {
            Path parentPath = current.getParent();

            if (parentPath != null && parentPath.startsWith(fileService.getRootPath())) {
                parent = fileService.getRootPath().equals(parentPath)
                        ? ""
                        : fileService.getRootPath().relativize(parentPath).toString().replace("\\\\", "/");
            }
        }

        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.min(Math.max(1, limit), 300);

        List<FileItemDto> items = fileService.list(path, safeOffset, safeLimit);

        /*
         * Возвращаем Map.
         * Spring через Jackson автоматически сериализует ее в JSON.
         */
        return Map.of(
                "currentPath", normalized,
                "parentPath", parent,
                "items", items,
                "total", total
        );
    }
    @GetMapping("/upload/status")
    public ResponseEntity<?> uploadStatus(@RequestParam String uploadId) throws IOException {
        UploadSessionDto meta = readMeta(uploadId);

        if (meta == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(meta);
    }

    /*
     * POST /api/files/upload?path=...
     *
     * Загружает файлы в выбранную папку.
     */
    @PostMapping("/upload/init")
    public ResponseEntity<?> initUpload(
            @RequestParam String fileName,
            @RequestParam long fileSize,
            @RequestParam long chunkSize,
            @RequestParam(defaultValue = "") String path,
            @RequestParam long lastModified
    ) throws IOException {

        if (fileSize <= 0 || chunkSize <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid fileSize or chunkSize"));
        }

        int totalChunks = (int) Math.ceil((double) fileSize / chunkSize);
        String uploadId = fileName + "_" + fileSize + "_" + lastModified;

        UploadSessionDto meta = readMeta(uploadId);

        if (meta == null) {
            meta = new UploadSessionDto();
            meta.setUploadId(uploadId);
            meta.setFileName(fileName);
            meta.setTargetPath(path);
            meta.setFileSize(fileSize);
            meta.setChunkSize(chunkSize);
            meta.setTotalChunks(totalChunks);
            meta.setUploadedChunks(new java.util.ArrayList<>());

            writeMeta(meta);
        }

        return ResponseEntity.ok(meta);
    }
    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> upload(
            @RequestParam(defaultValue = "") String path,
            @RequestParam("files") MultipartFile[] files
    ) throws IOException {

        /*
         * ResponseEntity позволяет управлять:
         * - статус-кодом
         * - заголовками
         * - телом ответа
         */
        fileService.upload(path, files);

        return ResponseEntity.ok(Map.of("message", "Uploaded"));
    }

    /*
     * POST /api/files/folder?path=...
     *
     * Создает новую папку внутри текущей директории.
     */
    @PostMapping("/folder")
    public ResponseEntity<Map<String, String>> createFolder(
            @RequestParam(defaultValue = "") String path,
            @RequestParam String name
    ) throws IOException {
        fileService.createFolder(path, name);
        fileService.rebuildFolderTreeCache();
        /*metadataService.clearFolderCache();*/
        return ResponseEntity.ok(Map.of("message", "Folder created"));
    }

    /*
     * DELETE /api/files?path=...
     *
     * Удаляет файл или папку.
     */
    @DeleteMapping
    public ResponseEntity<Map<String, String>> delete(@RequestParam String path) throws IOException {
        fileService.delete(path);
        fileService.rebuildFolderTreeCache();
        /*metadataService.clearFolderCache();*/
        return ResponseEntity.ok(Map.of("message", "Deleted"));
    }
    @GetMapping("/image-thumbnail")
    public ResponseEntity<Resource> imageThumbnail(@RequestParam String path) throws IOException {
        Path file = fileService.resolveSafe(path);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
        }
        Path thumbnail;

        try {
            thumbnail = thumbnailService.getOrCreateImageThumbnail(file);
        } catch (Exception e) {
            System.out.println("⚠️ Image thumbnail failed: " + file);
            thumbnail = null;
        }

        if (thumbnail != null && Files.exists(thumbnail) && Files.size(thumbnail) > 0) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                    .contentType(MediaType.IMAGE_JPEG)
                    .contentLength(Files.size(thumbnail))
                    .body(new FileSystemResource(thumbnail));
        }

        ClassPathResource placeholder = new ClassPathResource("static/image-placeholder.png");

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(placeholder.contentLength())
                .body(placeholder);
    }

    /*
     * GET /api/files/download?path=...
     *
     * Отдает файл как attachment,
     * чтобы браузер предложил скачать его.
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam String path) throws IOException {
        Path file = fileService.resolveSafe(path);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
        }

        /*
         * FileSystemResource — оболочка Spring над обычным файлом на диске.
         */
        Resource resource = new FileSystemResource(file);

        return ResponseEntity.ok()
                /*
                 * Content-Disposition: attachment
                 * говорит браузеру скачивать файл,
                 * а не пытаться открыть его внутри вкладки.
                 */
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getFileName() + "\"")
                .contentLength(Files.size(file))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    /*
     * GET /api/files/raw?path=...
     *
     * Отдает файл напрямую в браузер.
     * Нужно для картинок и видео.
     */
    @GetMapping("/raw")
    public ResponseEntity<Resource> raw(@RequestParam String path) throws IOException {
        Path file = fileService.resolveSafe(path);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
        }
        String fileName = file.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".heic") || fileName.endsWith(".heif")) {
            Path jpgPreview = thumbnailService.getOrCreateHeicThumbnail(file);

            if (jpgPreview == null || !Files.exists(jpgPreview) || Files.size(jpgPreview) == 0) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(null);
            }

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                    .contentType(MediaType.IMAGE_JPEG)
                    .contentLength(Files.size(jpgPreview))
                    .body(new FileSystemResource(jpgPreview));
        }

        FileSystemResource resource = new FileSystemResource(file);

        /*
         * Пытаемся определить реальный MIME-тип,
         * чтобы браузер понял, что это:
         * - image/jpeg
         * - video/mp4
         * - и т.д.
         */
        String contentType = Files.probeContentType(file);

        MediaType mediaType = contentType != null
                ? MediaType.parseMediaType(contentType)
                : MediaType.APPLICATION_OCTET_STREAM;

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(Files.size(file))
                .body(resource);
    }
    @GetMapping("/stream")
    public ResponseEntity<ResourceRegion> stream(
            @RequestParam String path,
            @RequestHeader HttpHeaders requestHeaders
    ) throws IOException {

        Path file = fileService.resolveSafe(path);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
        }

        FileSystemResource video = new FileSystemResource(file);
        long fileSize = video.contentLength();

        String contentType = Files.probeContentType(file);
        MediaType mediaType = contentType != null
                ? MediaType.parseMediaType(contentType)
                : MediaType.APPLICATION_OCTET_STREAM;

        List<HttpRange> ranges = requestHeaders.getRange();

        ResourceRegion region;

        if (ranges.isEmpty()) {
            long chunkSize = Math.min(1024 * 1024, fileSize);
            region = new ResourceRegion(video, 0, chunkSize);

            return ResponseEntity.status(HttpStatus.OK)
                    .contentType(mediaType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(region);
        }

        HttpRange range = ranges.get(0);

        long start = range.getRangeStart(fileSize);
        long end = range.getRangeEnd(fileSize);

        long rangeLength = Math.min(1024 * 1024, end - start + 1);

        region = new ResourceRegion(video, start, rangeLength);

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(region);
    }
    @PostMapping("/download/mp4-start")
    public ResponseEntity<?> startDownloadMp4(@RequestParam String path) throws IOException {
        Path source = fileService.resolveSafe(path);

        if (!Files.exists(source) || Files.isDirectory(source)) {
            return ResponseEntity.notFound().build();
        }

        String name = source.getFileName().toString();
        String lowerName = name.toLowerCase();

        if (!lowerName.endsWith(".lrv") && !lowerName.endsWith(".insv")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only LRV/INSV supported"));
        }

        String previewId = UUID.randomUUID().toString();

        String baseName = name.substring(0, name.lastIndexOf('.'));
        Path renamed = source.getParent().resolve(baseName + "." + previewId + ".download.mp4");

        RenamePreviewSessionDto session = new RenamePreviewSessionDto();
        session.setPreviewId(previewId);
        session.setOriginalPath(source.toAbsolutePath().toString());
        session.setRenamedPath(renamed.toAbsolutePath().toString());
        session.setStatus("OPEN");

        writePreviewJournal(session);

        Files.move(source, renamed, StandardCopyOption.ATOMIC_MOVE);

        previewFiles.put(previewId, renamed);
        previewProgress.put(previewId, 100);

        return ResponseEntity.ok(Map.of("previewId", previewId));
    }
    @GetMapping("/download/mp4-file")
    public ResponseEntity<Resource> downloadMp4File(@RequestParam String previewId) throws IOException {
        Path file = previewFiles.get(previewId);

        if (file == null || !Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }

        RenamePreviewSessionDto session = readPreviewJournal(getPreviewJournalFile(previewId));
        String originalName = Path.of(session.getOriginalPath()).getFileName().toString();

        String baseName = originalName.replaceFirst("\\.[^.]+$", "");
        String downloadName = baseName + ".mp4";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(file))
                .body(new FileSystemResource(file));
    }
    @PostMapping("/metadata/bulk")
    public ResponseEntity<?> metadataBulk(@RequestBody List<String> paths) {

        Map<String, Long> result = new HashMap<>();

        for (String rel : paths) {
            try {
                Path file = fileService.resolveSafe(rel);

                if (Files.isDirectory(file)) continue;

                long created = metadataService.readCreatedAtFromPropertiesCache(file);

                result.put(rel, created);

            } catch (Exception ignored) {
            }
        }

        return ResponseEntity.ok(result);
    }
    @GetMapping("/properties/folder-stats")
    public ResponseEntity<?> folderStats(@RequestParam String path) throws IOException {
        Path folder = fileService.resolveSafe(path);

        if (!Files.exists(folder) || !Files.isDirectory(folder)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(metadataService.readFolderStats(folder));
    }
    @PostMapping("/move")
    public ResponseEntity<Map<String, String>> move(
            @RequestParam String sourcePath,
            @RequestParam String targetPath
    ) throws IOException {
        fileService.move(sourcePath, targetPath);
        fileService.rebuildFolderTreeCache();
        return ResponseEntity.ok(Map.of("message", "Moved"));
    }
    @GetMapping("/properties")
    public ResponseEntity<?> properties(@RequestParam String path) throws IOException {
        Path file = fileService.resolveSafe(path);

        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(metadataService.readFileProperties(file));
    }
    @DeleteMapping("/clear-temp")
    public ResponseEntity<?> clearTemp() throws IOException {
        Path tempDir = fileService.getRootPath().resolve(".upload_tmp");

        if (Files.exists(tempDir)) {
            try (var stream = Files.walk(tempDir)) {
                stream
                        .sorted(Comparator.reverseOrder()) // сначала файлы, потом папки
                        .forEach(path -> {
                            try {
                                if (!path.equals(tempDir)) {
                                    Files.deleteIfExists(path);
                                }
                            } catch (IOException e) {
                                System.out.println("Failed to delete: " + path);
                            }
                        });
            }
        }

        return ResponseEntity.ok().build();
    }
    @GetMapping("/folders/tree")
    public FolderNodeDto folderTree() throws IOException {
        return fileService.getFolderTreeCached();
    }
    /*endpoint загрузки чанков*/
    @PostMapping("/upload-chunk")
    public ResponseEntity<?> uploadChunk(
            @RequestParam String uploadId,
            @RequestParam int chunkIndex,
            @RequestParam("file") MultipartFile file
    ) throws IOException {

        if (chunkIndex < 0 || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid chunk"));
        }

        UploadSessionDto meta = readMeta(uploadId);
        if (meta == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Upload session not found",
                    "uploadId", uploadId
            ));
        }

        if (chunkIndex >= meta.getTotalChunks()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid chunk index"));
        }

        if (meta.getUploadedChunks() == null) {
            meta.setUploadedChunks(new java.util.ArrayList<>());
        }

        Path tempFile = getTempFile(uploadId);

        if (!Files.exists(tempFile)) {
            Files.createFile(tempFile);
        }

        Object lock = uploadLocks.computeIfAbsent(uploadId, k -> new Object());

        synchronized (lock) {
            if (meta.getUploadedChunks().contains(chunkIndex)) {
                return ResponseEntity.ok().build();
            }

            try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
                long position = (long) chunkIndex * meta.getChunkSize();
                raf.seek(position);
                raf.write(file.getBytes());
            }

            meta.getUploadedChunks().add(chunkIndex);
            meta.getUploadedChunks().sort(Integer::compareTo);
            writeMeta(meta);
        }

        return ResponseEntity.ok().build();
    }
    @PostMapping("/upload/complete")
    public ResponseEntity<?> completeUpload(@RequestParam String uploadId) throws IOException {
        UploadSessionDto meta = readMeta(uploadId);

        if (meta == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Upload session not found"));
        }

        Path tempFile = getTempFile(uploadId);

        Path finalDir = fileService.resolveSafe(meta.getTargetPath());
        Files.createDirectories(finalDir);

        Path finalPath = finalDir.resolve(meta.getFileName()).normalize();
        if (!finalPath.startsWith(fileService.getRootPath())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid target path"));
        }

        if (!Files.exists(tempFile)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Temp file not found"));
        }

        if (meta.getUploadedChunks().size() != meta.getTotalChunks()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Not all chunks uploaded"));
        }

        Files.move(tempFile, finalPath, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(getMetaFile(uploadId));

        uploadLocks.remove(uploadId);

        return ResponseEntity.ok(Map.of("message", "Upload completed"));
    }
    @GetMapping("/download-selected")
    public ResponseEntity<StreamingResponseBody> downloadSelected(
            @RequestParam List<String> paths
    ) {
        StreamingResponseBody body = outputStream -> {
            try (java.util.zip.ZipOutputStream zipOut = new java.util.zip.ZipOutputStream(outputStream)) {
                for (String path : paths) {
                    Path source = fileService.resolveSafe(path);

                    if (!Files.exists(source)) {
                        continue;
                    }

                    if (Files.isDirectory(source)) {
                        try (Stream<Path> walk = Files.walk(source)) {
                            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                                Path relative = source.getParent().relativize(file);
                                zipOut.putNextEntry(new java.util.zip.ZipEntry(relative.toString().replace("\\\\", "/")));
                                Files.copy(file, zipOut);
                                zipOut.closeEntry();
                            }
                        }
                    } else {
                        zipOut.putNextEntry(new java.util.zip.ZipEntry(source.getFileName().toString()));
                        Files.copy(source, zipOut);
                        zipOut.closeEntry();
                    }
                }
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"selected-files.zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }
    @GetMapping("/video-proxy")
    public void streamVideoProxy(@RequestParam String path, HttpServletResponse response) throws IOException {

        Path file = fileService.resolveSafe(path);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            response.setStatus(404);
            return;
        }

        response.setContentType("video/mp4");
        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-threads", "2",
                "-i", file.toAbsolutePath().toString(),

                "-vf", "scale=854:-2",
                "-r", "24",

                "-f", "mp4",
                "-movflags", "frag_keyframe+empty_moov+default_base_moof",

                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-tune", "zerolatency",
                "-crf", "32",
                "-pix_fmt", "yuv420p",

                "-c:a", "aac",
                "-b:a", "96k",

                "pipe:1"
        );
// ВАЖНО: НЕ объединять stderr со stdout
        pb.redirectErrorStream(false);

        Process process = pb.start();

// stderr читаем отдельно, чтобы ffmpeg не завис
        new Thread(() -> {
            try (InputStream err = process.getErrorStream()) {
                err.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
            }
        }).start();
        try (InputStream is = process.getInputStream();
             OutputStream os = response.getOutputStream()) {

            try {
                is.transferTo(os);
            } catch (IOException e) {
                if (!isClientDisconnect(e)) {
                    System.out.println("Video proxy streaming error: " + e.getMessage());
                }
            }

        } finally {
            process.destroyForcibly();
        }
    }
    @GetMapping("/video-thumbnail")
    public ResponseEntity<Resource> videoThumbnail(@RequestParam String path) throws IOException, InterruptedException {
        Path file = fileService.resolveSafe(path);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
        }

        Path thumbnail = thumbnailService.getOrCreateVideoThumbnail(file);
        if (thumbnail == null || !Files.exists(thumbnail) || Files.size(thumbnail) == 0) {
            System.out.println("⚠️ Video thumbnail unavailable, fallback on frontend: " + file);
            return ResponseEntity.noContent().build();
        }

        if (thumbnail != null && Files.exists(thumbnail) && Files.size(thumbnail) > 0) {
            return ResponseEntity.ok()
                    // 👇 ВОТ ЗДЕСЬ КЭШ
                    .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                    .contentType(MediaType.IMAGE_JPEG)
                    .contentLength(Files.size(thumbnail))
                    .body(new FileSystemResource(thumbnail));
        }
        // fallback (если нет превью)
        ClassPathResource placeholder = new ClassPathResource("static/video-placeholder.png");

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(placeholder.contentLength())
                .body(placeholder);

    }
    private boolean isClientDisconnect(Throwable e) {
        while (e != null) {
            String msg = e.getMessage();

            if (msg != null && (
                    msg.contains("разорвала установленное подключение")
                            || msg.contains("An established connection was aborted")
                            || msg.contains("Broken pipe")
                            || msg.contains("Connection reset by peer")
                            || msg.contains("ClientAbortException")
                            || msg.contains("AsyncRequestNotUsableException")
            )) {
                return true;
            }

            e = e.getCause();
        }

        return false;
    }
    private Path getPreviewJournalDir() throws IOException {
        Path dir = fileService.getRootPath().resolve(".preview_journal");
        Files.createDirectories(dir);
        return dir;
    }
    private Path getPreviewJournalFile(String previewId) throws IOException {
        Path journalDir = fileService.getRootPath().resolve(".preview_journal");
        Files.createDirectories(journalDir);
        return journalDir.resolve(previewId + ".json");
    }

    private void writePreviewJournal(RenamePreviewSessionDto session) throws IOException {
        objectMapper.writeValue(getPreviewJournalFile(session.getPreviewId()).toFile(), session);
    }

    private RenamePreviewSessionDto readPreviewJournal(Path file) throws IOException {
        return objectMapper.readValue(file.toFile(), RenamePreviewSessionDto.class);
    }
    @PostMapping("/metadata/card-bulk")
    public ResponseEntity<?> cardMetadataBulk(@RequestBody List<String> paths) {
        Map<String, Object> result = new HashMap<>();

        for (String relPath : paths) {
            try {
                Path path = fileService.resolveSafe(relPath);

                if (!Files.exists(path)) continue;

                Map<String, Object> item = new HashMap<>();

                if (Files.isDirectory(path)) {
                    long files = 0;
                    long folders = 0;

                    try (Stream<Path> stream = Files.list(path)) {
                        for (Path child : stream.toList()) {
                            if (Files.isDirectory(child)) folders++;
                            else files++;
                        }
                    }

                    item.put("directory", true);
                    item.put("fileCount", files);
                    item.put("folderCount", folders);
                } else {
                    item.put("directory", false);
                    item.put("createdAt", metadataService.readCreatedAtFromPropertiesCache(path));
                }

                result.put(relPath, item);

            } catch (Exception ignored) {
            }
        }

        return ResponseEntity.ok(result);
    }
    @PostMapping("/preview/rename-original-start")
    public ResponseEntity<?> startRenameOriginalPreview(@RequestParam String path) throws IOException {
        Path source = fileService.resolveSafe(path);

        if (!Files.exists(source) || Files.isDirectory(source)) {
            return ResponseEntity.notFound().build();
        }

        String name = source.getFileName().toString();
        String lowerName = name.toLowerCase();

        if (!lowerName.endsWith(".lrv") && !lowerName.endsWith(".insv")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only LRV/INSV supported"));
        }

        String previewId = UUID.randomUUID().toString();

        String baseName = name.substring(0, name.lastIndexOf('.'));
        Path renamed = source.getParent().resolve(baseName + "." + previewId + ".preview.mp4");

        RenamePreviewSessionDto session = new RenamePreviewSessionDto();
        session.setPreviewId(previewId);
        session.setOriginalPath(source.toAbsolutePath().toString());
        session.setRenamedPath(renamed.toAbsolutePath().toString());
        session.setStatus("OPEN");

        writePreviewJournal(session);

        Files.move(source, renamed, StandardCopyOption.ATOMIC_MOVE);

        previewFiles.put(previewId, renamed);
        previewProgress.put(previewId, 100);

        return ResponseEntity.ok(Map.of("previewId", previewId));
    }
    @PostConstruct
    public void restoreUnclosedRenamePreviews() {
        try {
            Path journalDir = getPreviewJournalDir();

            if (!Files.exists(journalDir)) {
                return;
            }

            try (Stream<Path> stream = Files.list(journalDir)) {
                stream
                        .filter(path -> path.toString().endsWith(".json"))
                        .forEach(journalFile -> {
                            try {
                                RenamePreviewSessionDto session = readPreviewJournal(journalFile);

                                if (!"OPEN".equals(session.getStatus())) {
                                    return;
                                }

                                Path original = Path.of(session.getOriginalPath());
                                Path renamed = Path.of(session.getRenamedPath());

                                if (Files.exists(renamed) && !Files.exists(original)) {
                                    Files.move(renamed, original, StandardCopyOption.REPLACE_EXISTING);
                                }

                                session.setStatus("CLOSED");
                                writePreviewJournal(session);

                            } catch (Exception e) {
                                System.out.println("Failed to restore preview journal: " + journalFile);
                                e.printStackTrace();
                            }
                        });
            }

        } catch (Exception e) {
            System.out.println("Preview restore failed");
            e.printStackTrace();
        }
    }

}
