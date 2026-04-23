package ru.homeserver.photoshare.homeserver.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.homeserver.photoshare.homeserver.dto.FolderNodeDto;
import ru.homeserver.photoshare.homeserver.dto.FileItemDto;
import ru.homeserver.photoshare.homeserver.dto.UploadSessionDto;
import ru.homeserver.photoshare.homeserver.service.FileService;
import ru.homeserver.photoshare.homeserver.service.ThumbnailService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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


    public FileController(FileService fileService, ThumbnailService thumbnailService) {
        this.fileService = fileService;
        this.thumbnailService = thumbnailService;
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
    @PostMapping("/move")
    public ResponseEntity<Map<String, String>> move(
            @RequestParam String sourcePath,
            @RequestParam String targetPath
    ) throws IOException {
        fileService.move(sourcePath, targetPath);
        return ResponseEntity.ok(Map.of("message", "Moved"));
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
        return fileService.getFolderTree();
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
            return ResponseEntity.badRequest().body(Map.of("error", "Upload session not found"));
        }

        if (chunkIndex >= meta.getTotalChunks()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid chunk index"));
        }

        Path tempFile = getTempFile(uploadId);

        Object lock = uploadLocks.computeIfAbsent(uploadId, k -> new Object());

        synchronized (lock) {
            // если этот чанк уже есть — просто ничего не делаем
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
    /*@PostMapping("/upload-chunk")
    public ResponseEntity<?> uploadChunk(
            @RequestParam String uploadId,
            @RequestParam int chunkIndex,
            @RequestParam int totalChunks,
            @RequestParam String fileName,
            @RequestParam long chunkSize,
            @RequestParam(defaultValue = "") String path,
            @RequestParam("file") MultipartFile file
    ) throws IOException {

        if (chunkIndex < 0 || totalChunks <= 0 || chunkIndex >= totalChunks) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid chunk index"));
        }

        if (chunkSize <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid chunk size"));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Empty chunk"));
        }

        Path uploadDir = fileService.getRootPath().resolve(".upload_tmp");
        Files.createDirectories(uploadDir);

        Path tempFile = uploadDir.resolve(uploadId + ".tmp");

        Path finalDir = fileService.resolveSafe(path);
        Files.createDirectories(finalDir);

        Path finalPath = finalDir.resolve(fileName).normalize();

        if (!finalPath.startsWith(fileService.getRootPath())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid target path"));
        }

        Object lock = uploadLocks.computeIfAbsent(uploadId, k -> new Object());
        boolean[] received = uploadProgress.computeIfAbsent(uploadId, k -> new boolean[totalChunks]);

        if (received.length != totalChunks) {
            return ResponseEntity.badRequest().body(Map.of("error", "Chunk metadata mismatch"));
        }

        synchronized (lock) {
            try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
                long position = (long) chunkIndex * chunkSize;
                raf.seek(position);
                raf.write(file.getBytes());
            }

            received[chunkIndex] = true;

            boolean allReceived = true;
            for (boolean part : received) {
                if (!part) {
                    allReceived = false;
                    break;
                }
            }

            if (allReceived) {
                if (Files.exists(tempFile) && Files.size(tempFile) > 0) {
                    try {
                        Files.move(tempFile, finalPath, StandardCopyOption.REPLACE_EXISTING);
                    } catch (NoSuchFileException e) {
                        System.out.println("Temp file not found during move: " + tempFile);
                    } finally {
                        uploadLocks.remove(uploadId);
                        uploadProgress.remove(uploadId);
                    }
                }
            }
        }

        return ResponseEntity.ok().build();
    }*/

    /*@PostMapping("/upload-chunk")
    public ResponseEntity<?> uploadChunk(
            @RequestParam String uploadId,
            @RequestParam int chunkIndex,
            @RequestParam int totalChunks,
            @RequestParam String fileName,
            @RequestParam long chunkSize,
            @RequestParam(defaultValue = "") String path,
            @RequestParam("file") MultipartFile file
    ) throws IOException {

        Path uploadDir = fileService.getRootPath().resolve(".upload_tmp");
        Files.createDirectories(uploadDir);

        Path tempFile = uploadDir.resolve(uploadId + ".tmp");

        Path finalDir = fileService.resolveSafe(path);
        Files.createDirectories(finalDir);

        Path finalPath = finalDir.resolve(fileName).normalize();

        if (!finalPath.startsWith(fileService.getRootPath())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid target path"));
        }

        Object lock = uploadLocks.computeIfAbsent(uploadId, k -> new Object());
        boolean[] received = uploadProgress.computeIfAbsent(uploadId, k -> new boolean[totalChunks]);

        synchronized (lock) {
            try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
                long position = (long) chunkIndex * chunkSize;
                raf.seek(position);
                raf.write(file.getBytes());
            }

            received[chunkIndex] = true;

            boolean allReceived = true;
            for (boolean part : received) {
                if (!part) {
                    allReceived = false;
                    break;
                }
            }

            if (allReceived) {
                if (Files.exists(tempFile) && Files.size(tempFile) > 0) {
                    try {
                        Files.move(tempFile, finalPath, StandardCopyOption.REPLACE_EXISTING);
                    } catch (NoSuchFileException e) {
                        System.out.println("Temp file not found during move: " + tempFile);
                    } finally {
                        uploadLocks.remove(uploadId);
                        uploadProgress.remove(uploadId);
                    }
                }
            }
        }

        return ResponseEntity.ok().build();
    }*/
    /*@PostMapping("/upload-chunk")
    public ResponseEntity<?> uploadChunk(
            @RequestParam String uploadId,
            @RequestParam int chunkIndex,
            @RequestParam int totalChunks,
            @RequestParam String fileName,
            @RequestParam long chunkSize,
            @RequestParam(defaultValue = "") String path,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        Path finalPath = Paths.get("storage").resolve(fileName);
        Path uploadDir = Paths.get("uploads_tmp");
        Files.createDirectories(uploadDir);
        Object lock = uploadLocks.computeIfAbsent(uploadId, k -> new Object());
        Path tempFile = uploadDir.resolve(uploadId + ".tmp");
        synchronized (lock) {
        try (RandomAccessFile raf = new RandomAccessFile(tempFile.toFile(), "rw")) {
            *//*long position = (long) chunkIndex * file.getSize();*//*
            long position = (long) chunkIndex * chunkSize;
            raf.seek(position);
            raf.write(file.getBytes());
        }

        // если последний кусок
            if (Files.exists(tempFile) && Files.size(tempFile) > 0) {
                try {
                    Files.move(tempFile, finalPath, StandardCopyOption.REPLACE_EXISTING);
                } catch (NoSuchFileException e) {
                    System.out.println("Temp file not found during move: " + tempFile);
                } finally {
                    uploadLocks.remove(uploadId);
                }
            }
        *//*if (chunkIndex == totalChunks - 1) {
            Path finalPath = Paths.get("storage").resolve(fileName);
            Files.move(tempFile, finalPath, StandardCopyOption.REPLACE_EXISTING);
        }*//*

        return ResponseEntity.ok().build();
    }*/
    /*
     * GET /api/files/video-thumbnail?path=...
     *
     * Возвращает jpg-миниатюру для видео.
     */
    @GetMapping("/video-thumbnail")
    public ResponseEntity<Resource> videoThumbnail(@RequestParam String path) throws IOException {
        Path file = fileService.resolveSafe(path);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
        }

        Path thumbnail = thumbnailService.getOrCreateVideoThumbnail(file);

        /*if (thumbnail == null || !Files.exists(thumbnail)) {
            return ResponseEntity.notFound().build();
        }*/
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

}