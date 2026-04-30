package ru.homeserver.photoshare.homeserver.controller;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.homeserver.photoshare.homeserver.dto.FolderNodeDto;
import ru.homeserver.photoshare.homeserver.dto.FileItemDto;
import ru.homeserver.photoshare.homeserver.dto.RenamePreviewSessionDto;
import ru.homeserver.photoshare.homeserver.dto.UploadSessionDto;
import ru.homeserver.photoshare.homeserver.service.FileService;
//import ru.homeserver.photoshare.homeserver.service.MetadataService;
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


    private final String ffmpegPath;
    private final String ffprobePath;

    private final Map<String, Process> previewProcesses = new ConcurrentHashMap<>();
    private final Map<String, Integer> previewProgress = new ConcurrentHashMap<>();
    private final Map<String, Path> previewFiles = new ConcurrentHashMap<>();
    private final MetadataService metadataService;

    public FileController(
            FileService fileService,
            ThumbnailService thumbnailService,
            @Value("${app.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${app.ffprobe-path:ffprobe}") String ffprobePath, MetadataService metadataService
    ) {
        this.fileService = fileService;
        this.thumbnailService = thumbnailService;
        this.ffmpegPath = ffmpegPath;
        this.ffprobePath = ffprobePath;
        this.metadataService = metadataService;
    }
    private Path getUploadTempDir() throws IOException {
        Path dir = fileService.getRootPath().resolve(".upload_tmp");
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

    @PostMapping("/preview/rename-start")
    public ResponseEntity<?> startRenamePreview(@RequestParam String path) throws IOException {
        Path source = fileService.resolveSafe(path);

        if (!Files.exists(source) || Files.isDirectory(source)) {
            return ResponseEntity.notFound().build();
        }

        String previewId = UUID.randomUUID().toString();

        Path tempDir = fileService.getRootPath().resolve(".upload_tmp");
        Files.createDirectories(tempDir);

        Path output = tempDir.resolve(previewId + ".preview.mp4");

        previewFiles.put(previewId, output);
        previewProgress.put(previewId, 0);

        new Thread(() -> {
            try (InputStream in = Files.newInputStream(source);
                 OutputStream out = Files.newOutputStream(output,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING)) {

                byte[] buffer = new byte[1024 * 1024];
                long copied = 0;
                long total = Files.size(source);

                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    copied += read;

                    int percent = (int) Math.min(99, copied * 100 / total);
                    previewProgress.put(previewId, percent);
                }
                out.flush();
                previewProgress.put(previewId, 100);

            } catch (Exception e) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                }
                previewProgress.put(previewId, -1);
            }
        }).start();

        return ResponseEntity.ok(Map.of("previewId", previewId));
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

        Path tempDir = fileService.getRootPath().resolve(".upload_tmp");
        Files.createDirectories(tempDir);

        Path output = tempDir.resolve(previewId + ".preview.mp4");

        previewProgress.put(previewId, 0);
        previewFiles.put(previewId, output);

        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        ffmpegPath,
                        "-y",

                        "-ss","00:00:00",
                        "-analyzeduration","10M",
                        "-probesize","10M",
                        "-i", source.toAbsolutePath().toString(),

                        "-t", "00:01:00",

                        "-vf", "scale=854:-2",
                        "-r", "24",

                        "-c:v", "libx264",
                        "-preset", "ultrafast",
                        "-crf", "32",
                        "-pix_fmt", "yuv420p",

                        "-c:a", "aac",
                        "-b:a", "96k",

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
    public ResponseEntity<StreamingResponseBody> previewFile(
            @RequestParam String previewId,
            @RequestHeader(value = "Range", required = false) String rangeHeader
    ) throws IOException {

        Path file = previewFiles.get(previewId);

        if (file == null || !Files.exists(file) || Files.size(file) == 0) {
            return ResponseEntity.notFound().build();
        }

        long fileSize = Files.size(file);

        long start = 0;
        long end = fileSize - 1;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String rangeValue = rangeHeader.substring("bytes=".length()).trim();
            String[] parts = rangeValue.split("-", 2);

            if (!parts[0].isBlank()) {
                start = Long.parseLong(parts[0]);
            }

            if (parts.length > 1 && !parts[1].isBlank()) {
                end = Long.parseLong(parts[1]);
            }
        }

        end = Math.min(end, fileSize - 1);
        long contentLength = end - start + 1;

        long finalStart = start;
        long finalEnd = end;

        StreamingResponseBody body = outputStream -> {
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(finalStart);

                byte[] buffer = new byte[8192];
                long bytesLeft = contentLength;

                while (bytesLeft > 0) {
                    int bytesToRead = (int) Math.min(buffer.length, bytesLeft);
                    int bytesRead = raf.read(buffer, 0, bytesToRead);

                    if (bytesRead == -1) break;

                    outputStream.write(buffer, 0, bytesRead);
                    bytesLeft -= bytesRead;
                }
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("video/mp4"));
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength));
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileSize);

        return ResponseEntity
                .status(rangeHeader == null ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT)
                .headers(headers)
                .body(body);
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
    public Map<String, Object> list(@RequestParam(defaultValue = "") String path) throws IOException {
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

        /*
         * normalized — текущий путь в удобном для фронтенда виде.
         * Если это rootPath, то возвращаем пустую строку.
         */
        String normalized = fileService.getRootPath().equals(current)
                ? ""
                : fileService.getRootPath().relativize(current).toString().replace("\\", "/");

        /*
         * parent — относительный путь к родительской папке.
         * Нужен кнопке "Назад".
         */
        String parent = "";
        if (StringUtils.hasText(normalized)) {
            Path parentPath = current.getParent();

            if (parentPath != null && parentPath.startsWith(fileService.getRootPath())) {
                parent = fileService.getRootPath().equals(parentPath)
                        ? ""
                        : fileService.getRootPath().relativize(parentPath).toString().replace("\\", "/");
            }
        }

        List<FileItemDto> items = fileService.list(path);

        /*
         * Возвращаем Map.
         * Spring через Jackson автоматически сериализует ее в JSON.
         */
        return Map.of(
                "currentPath", normalized,
                "parentPath", parent,
                "items", items
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
        metadataService.clearFolderCache();
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
        metadataService.clearFolderCache();
        return ResponseEntity.ok(Map.of("message", "Deleted"));
    }
    @GetMapping("/image-thumbnail")
    public ResponseEntity<Resource> imageThumbnail(@RequestParam String path) throws IOException {
        Path file = fileService.resolveSafe(path);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
        }

        Path thumbnail = thumbnailService.getOrCreateImageThumbnail(file);

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
public ResponseEntity<StreamingResponseBody> stream(
        @RequestParam String path,
        @RequestHeader(value = "Range", required = false) String rangeHeader) throws IOException {

    Path file = fileService.resolveSafe(path);

    if (!Files.exists(file) || Files.isDirectory(file)) {
        return ResponseEntity.notFound().build();
    }

    long fileSize = Files.size(file);

    String contentType = Files.probeContentType(file);
    MediaType mediaType = contentType != null
            ? MediaType.parseMediaType(contentType)
            : MediaType.APPLICATION_OCTET_STREAM;

    long start = 0;
    long end = fileSize - 1;

    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
        String rangeValue = rangeHeader.substring("bytes=".length()).trim();
        String[] parts = rangeValue.split("-", 2);

        try {
            if (!parts[0].isBlank()) {
                start = Long.parseLong(parts[0]);
            }

            if (parts.length > 1 && !parts[1].isBlank()) {
                end = Long.parseLong(parts[1]);
            } else {
                end = fileSize - 1;
            }
        } catch (NumberFormatException e) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + fileSize)
                    .build();
        }
    }

    if (start < 0 || start >= fileSize || end < start) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + fileSize)
                .build();
    }

    end = Math.min(end, fileSize - 1);
    long contentLength = end - start + 1;

    long finalStart = start;
    long finalEnd = end;


    StreamingResponseBody responseBody = outputStream -> {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(finalStart);

            byte[] buffer = new byte[8192];
            long bytesLeft = contentLength;

            while (bytesLeft > 0) {
                int bytesToRead = (int) Math.min(buffer.length, bytesLeft);
                int bytesRead = raf.read(buffer, 0, bytesToRead);

                if (bytesRead == -1) {
                    break;
                }

                try {
                    outputStream.write(buffer, 0, bytesRead);
                } catch (IOException e) {
                    if (isClientDisconnect(e)) {
                        System.out.println("Client disconnected during streaming: " + e.getMessage());
                        return;
                    }
                    throw e;
                }

                bytesLeft -= bytesRead;
            }

            try {
                outputStream.flush();
            } catch (IOException e) {
                if (isClientDisconnect(e)) {
                    System.out.println("Client disconnected during flush: " + e.getMessage());
                    return;
                }
                throw e;
            }
        } catch (IOException e) {
            if (isClientDisconnect(e)) {
                System.out.println("Client disconnected outer catch: " + e.getMessage());
                return;
            }
            throw e;
        }
    };

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(mediaType);
    headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
    headers.set(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength));
    headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileSize);

    return ResponseEntity.status(rangeHeader == null ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT)
            .headers(headers)
            .body(responseBody);
}
    @GetMapping("/metadata/created-at")
    public ResponseEntity<?> createdAt(@RequestParam String path) throws IOException {
        Path file = fileService.resolveSafe(path);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
        }

        long createdAt = metadataService.readCreatedAtMillisCached(file);

        return ResponseEntity.ok(Map.of(
                "path", path,
                "createdAt", createdAt
        ));
    }
    @PostMapping("/metadata/bulk")
    public ResponseEntity<?> metadataBulk(@RequestBody List<String> paths) {

        Map<String, Long> result = new HashMap<>();

        for (String rel : paths) {
            try {
                Path file = fileService.resolveSafe(rel);

                if (Files.isDirectory(file)) continue;

                long created = metadataService.readCreatedAtMillisCached(file);

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
        metadataService.clearFolderCache();
        return ResponseEntity.ok(Map.of("message", "Moved"));
    }
    @GetMapping("/properties")
    public ResponseEntity<?> properties(@RequestParam String path) throws IOException {
        Path file = fileService.resolveSafe(path);

        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }

        /*return ResponseEntity.ok(thumbnailService.readFileProperties(file));*/
        return ResponseEntity.ok(metadataService.readFileProperties(file));
    }
    /*@GetMapping("/properties")
    public ResponseEntity<?> properties(@RequestParam String path) throws IOException {
        Path file = fileService.resolveSafe(path);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> props = thumbnailService.readFileProperties(file);

        return ResponseEntity.ok(props);
    }*/

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
        /*return fileService.getFolderTree();*/
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
                                zipOut.putNextEntry(new java.util.zip.ZipEntry(relative.toString().replace("\\", "/")));
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

            is.transferTo(os);
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
    private boolean isClientDisconnect(IOException e) {
        String msg = e.getMessage();
        return msg != null && (
                msg.contains("разорвала установленное подключение")
                        || msg.contains("An established connection was aborted")
                        || msg.contains("Broken pipe")
                        || msg.contains("Connection reset by peer")
        );
    }
    private Path getPreviewJournalDir() throws IOException {
        Path dir = fileService.getRootPath().resolve(".preview_journal");
        Files.createDirectories(dir);
        return dir;
    }

    private Path getPreviewJournalFile(String previewId) throws IOException {
        return getPreviewJournalDir().resolve(previewId + ".json");
    }

    private void writePreviewJournal(RenamePreviewSessionDto session) throws IOException {
        objectMapper.writeValue(getPreviewJournalFile(session.getPreviewId()).toFile(), session);
    }

    private RenamePreviewSessionDto readPreviewJournal(Path file) throws IOException {
        return objectMapper.readValue(file.toFile(), RenamePreviewSessionDto.class);
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