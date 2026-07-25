package ru.homeserver.photoshare.homeserver.share;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.*;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.homeserver.photoshare.homeserver.config.VideoPreviewProperties;
import ru.homeserver.photoshare.homeserver.dto.FileItemDto;
import ru.homeserver.photoshare.homeserver.dto.FolderPrepareJob;
import ru.homeserver.photoshare.homeserver.dto.UploadSessionDto;
import ru.homeserver.photoshare.homeserver.service.FileService;
import ru.homeserver.photoshare.homeserver.service.FolderPrepareService;
import ru.homeserver.photoshare.homeserver.service.ThumbnailService;
import ru.homeserver.photoshare.homeserver.util.CloudAccessLogService;
import ru.homeserver.photoshare.homeserver.video.HlsConversionService;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/share/{token}")
public class SharePublicController {

    private final CloudAccessLogService cloudAccessLogService;
    private final ShareService shareService;
    private final FileService fileService;
    private final ThumbnailService thumbnailService;
    private final FolderPrepareService folderPrepareService;
    private final HlsConversionService hlsConversionService;
    private final VideoPreviewProperties videoPreviewProperties;
    private final Map<String, Object> uploadLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final ru.homeserver.photoshare.homeserver.config.AppProperties appProperties;
    public SharePublicController(
            CloudAccessLogService cloudAccessLogService, ShareService shareService,
            FileService fileService,
            ThumbnailService thumbnailService,
            FolderPrepareService folderPrepareService, HlsConversionService hlsConversionService, VideoPreviewProperties videoPreviewProperties, ru.homeserver.photoshare.homeserver.config.AppProperties appProperties
    ) {
        this.cloudAccessLogService = cloudAccessLogService;
        this.shareService = shareService;
        this.fileService = fileService;
        this.thumbnailService = thumbnailService;
        this.folderPrepareService = folderPrepareService;
        this.hlsConversionService = hlsConversionService;
        this.videoPreviewProperties = videoPreviewProperties;
        this.appProperties = appProperties;
    }

    @GetMapping("/info")
    public ResponseEntity<?> info(@PathVariable String token) throws IOException {
        ShareLink link = shareService.requireActive(token);

        return ResponseEntity.ok(Map.of(
                "token", link.getToken(),
                "path", link.getPath(),
                "directory", link.isDirectory(),
                "permission", link.getPermission(),
                "canDownload", link.getPermission().canDownload(),
                "canUpload", link.getPermission().canUpload(),
                "canDelete", link.getPermission().canDelete()
        ));
    }

    @PostMapping("/video/hls/prepare")
    public ResponseEntity<?> prepareHls(
            @PathVariable String token,
            @RequestParam(defaultValue = "") String path
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);

        String realPath = shareService.resolveInsideShare(link, path);
        Path sourceFile = fileService.resolveSafe(realPath);

        if (!Files.exists(sourceFile) || Files.isDirectory(sourceFile)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(
                hlsConversionService.prepareHls(
                        sourceFile,
                        "/share/" + token + "/video/hls/files"
                )
        );
    }

    @GetMapping("/video/hls/status")
    public ResponseEntity<?> hlsStatus(
            @PathVariable String token,
            @RequestParam(defaultValue = "") String path
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);

        String realPath = shareService.resolveInsideShare(link, path);
        Path sourceFile = fileService.resolveSafe(realPath);

        if (!Files.exists(sourceFile) || Files.isDirectory(sourceFile)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(
                hlsConversionService.getStatus(sourceFile)
        );
    }

    @GetMapping("/video/hls/progress")
    public ResponseEntity<?> hlsProgress(
            @PathVariable String token,
            @RequestParam(defaultValue = "") String path
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);

        String realPath = shareService.resolveInsideShare(link, path);
        Path sourceFile = fileService.resolveSafe(realPath);

        if (!Files.exists(sourceFile) || Files.isDirectory(sourceFile)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(
                hlsConversionService.getProgress(sourceFile)
        );
    }

    @DeleteMapping("/video/hls/cancel")
    public ResponseEntity<?> cancelHls(
            @PathVariable String token,
            @RequestParam(defaultValue = "") String path
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);

        String realPath = shareService.resolveInsideShare(link, path);
        Path sourceFile = fileService.resolveSafe(realPath);

        if (!Files.exists(sourceFile) || Files.isDirectory(sourceFile)) {
            return ResponseEntity.notFound().build();
        }

        hlsConversionService.cancel(sourceFile);

        return ResponseEntity.ok().build();
    }
    @GetMapping("/video/hls/files/{key}/{filename}")
    public ResponseEntity<Resource> publicHlsFile(
            @PathVariable String token,
            @PathVariable String key,
            @PathVariable String filename
    ) throws IOException {
        shareService.requireActive(token);

        Path folder = videoPreviewProperties.getHlsCacheDir()
                .resolve(key)
                .normalize();

        Path file = folder.resolve(filename).normalize();

        if (!file.startsWith(folder) || !Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }

        MediaType mediaType = detectHlsMediaType(filename);

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL,
                        "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .contentType(mediaType)
                .body(new FileSystemResource(file));
    }

    private MediaType detectHlsMediaType(String filename) {
        if (filename.endsWith(".m3u8")) {
            return MediaType.parseMediaType("application/vnd.apple.mpegurl");
        }

        if (filename.endsWith(".m4s")) {
            return MediaType.parseMediaType("video/iso.segment");
        }

        if (filename.endsWith(".mp4")) {
            return MediaType.parseMediaType("video/mp4");
        }

        return MediaType.APPLICATION_OCTET_STREAM;
    }
    @GetMapping("/list")
    public ResponseEntity<?> list(
            @PathVariable String token,
            @RequestParam(defaultValue = "") String path,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);
        cloudAccessLogService.event("SHARE_LIST", request,
                "token=" + token + " path=" + path + " offset=" + offset + " limit=" + limit);
        if (!link.isDirectory()) {
            Path file = fileService.resolveSafe(link.getPath());

            if (!Files.exists(file)) {
                return ResponseEntity.notFound().build();
            }

            String parentPath = file.getParent() == null
                    ? ""
                    : fileService.toRelative(file.getParent());

            List<FileItemDto> items = fileService.list(parentPath, 0, 1000)
                    .stream()
                    .filter(item -> item.relativePath().equals(link.getPath()))
                    .map(item -> shareService.toPublicItem(link, item))
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "currentPath", "",
                    "items", items,
                    "total", items.size(),
                    "permission", link.getPermission(),
                    "singleFile", true
            ));
        }

        String realPath = shareService.resolveInsideShare(link, path);

        List<FileItemDto> items = fileService.list(realPath, offset, limit)
                .stream()
                .map(item -> shareService.toPublicItem(link, item))
                .toList();

        return ResponseEntity.ok(Map.of(
                "currentPath", path == null ? "" : path,
                "items", items,
                "total", fileService.countItems(realPath),
                "permission", link.getPermission()
        ));
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(
            @PathVariable String token,
            @RequestParam(defaultValue = "") String path,
            HttpServletRequest request
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);

        if (!link.getPermission().canDownload()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String realPath = shareService.resolveInsideShare(link, path);
        Path file = fileService.resolveSafe(realPath);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
        }
        cloudAccessLogService.event("SHARE_DOWNLOAD_START", request,
                "token=" + token + " publicPath=" + path + " realPath=" + realPath + " size=" + Files.size(file));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getFileName() + "\"")
                .contentLength(Files.size(file))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    @GetMapping("/raw")
    public ResponseEntity<Resource> raw(
            @PathVariable String token,
            @RequestParam(defaultValue = "") String path,
            HttpServletRequest request
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);

        String realPath = shareService.resolveInsideShare(link, path);
        Path file = fileService.resolveSafe(realPath);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
        }
        cloudAccessLogService.event("SHARE_RAW_OPEN", request,
                "token=" + token + " publicPath=" + path + " realPath=" + realPath + " size=" + Files.size(file));
        String fileName = file.getFileName().toString().toLowerCase();

        if (fileName.endsWith(".heic") || fileName.endsWith(".heif")) {
            Path jpgPreview = thumbnailService.getOrCreateHeicThumbnail(file);

            if (jpgPreview == null || !Files.exists(jpgPreview) || Files.size(jpgPreview) == 0) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
            }

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                    .contentType(MediaType.IMAGE_JPEG)
                    .contentLength(Files.size(jpgPreview))
                    .body(new FileSystemResource(jpgPreview));
        }

        String contentType = Files.probeContentType(file);

        MediaType mediaType = contentType != null
                ? MediaType.parseMediaType(contentType)
                : MediaType.APPLICATION_OCTET_STREAM;

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(Files.size(file))
                .body(new FileSystemResource(file));
    }

    @GetMapping("/stream")
    public ResponseEntity<ResourceRegion> stream(
            @PathVariable String token,
            @RequestParam(defaultValue = "") String path,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest request
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);

        String realPath = shareService.resolveInsideShare(link, path);
        Path file = fileService.resolveSafe(realPath);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
        }

        FileSystemResource video = new FileSystemResource(file);
        long fileSize = video.contentLength();
        cloudAccessLogService.event("SHARE_STREAM", request,
                "token=" + token + " publicPath=" + path + " realPath=" + realPath + " size=" + fileSize + " range=" + headers.getRange());
        String contentType = Files.probeContentType(file);

        MediaType mediaType = contentType != null
                ? MediaType.parseMediaType(contentType)
                : MediaType.APPLICATION_OCTET_STREAM;

        List<HttpRange> ranges = headers.getRange();

        if (ranges.isEmpty()) {
            long chunkSize = Math.min(1024 * 1024, fileSize);

            return ResponseEntity.status(HttpStatus.OK)
                    .contentType(mediaType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(new ResourceRegion(video, 0, chunkSize));
        }

        HttpRange range = ranges.get(0);

        long start = range.getRangeStart(fileSize);
        long end = range.getRangeEnd(fileSize);
        long rangeLength = Math.min(1024 * 1024, end - start + 1);

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(new ResourceRegion(video, start, rangeLength));
    }

    @GetMapping("/thumbnail")
    public ResponseEntity<Resource> thumbnail(
            @PathVariable String token,
            @RequestParam(defaultValue = "") String path
    ) throws Exception {
        ShareLink link = shareService.requireActive(token);

        String realPath = shareService.resolveInsideShare(link, path);
        Path file = fileService.resolveSafe(realPath);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
        }

        String name = file.getFileName().toString().toLowerCase();

        Path thumbnail;

        if (name.matches(".*\\.(mp4|mov|avi|mkv|webm|m4v|ogv|insv|lrv)$")) {
            thumbnail = thumbnailService.getOrCreateVideoThumbnail(file);
        } else {
            thumbnail = thumbnailService.getOrCreateImageThumbnail(file);
        }

        if (thumbnail == null || !Files.exists(thumbnail) || Files.size(thumbnail) == 0) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(Files.size(thumbnail))
                .body(new FileSystemResource(thumbnail));
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(
            @PathVariable String token,
            @RequestParam(defaultValue = "") String path,
            @RequestParam("files") MultipartFile[] files,
            HttpServletRequest request
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);

        if (!link.getPermission().canUpload()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String realPath = shareService.resolveInsideShare(link, path);

        fileService.upload(realPath, files);
        cloudAccessLogService.event("SHARE_UPLOAD", request,
                "token=" + token + " path=" + path + " realPath=" + realPath + " files=" + files.length);
        folderPrepareService.invalidateFolderCache(realPath);

        return ResponseEntity.ok(Map.of("message", "Uploaded"));
    }

    @DeleteMapping
    public ResponseEntity<?> delete(
            @PathVariable String token,
            @RequestParam(defaultValue = "") String path,
            HttpServletRequest request
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);

        if (!link.getPermission().canDelete()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String realPath = shareService.resolveInsideShare(link, path);

        fileService.delete(realPath);
        cloudAccessLogService.event("SHARE_DELETE", request,
                "token=" + token + " path=" + path + " realPath=" + realPath);
        folderPrepareService.invalidateFolderCache(realPath);

        return ResponseEntity.ok(Map.of("message", "Deleted"));
    }
    @PostMapping("/folder")
    public ResponseEntity<?> createFolder(
            @PathVariable String token,
            @RequestParam(defaultValue = "") String path,
            @RequestParam String name
    ) throws IOException {

        ShareLink link = shareService.requireActive(token);

        if (!link.getPermission().canDelete()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String realPath = shareService.resolveInsideShare(link, path);

        fileService.createFolder(realPath, name);

        fileService.rebuildFolderTreeCache();

        folderPrepareService.invalidateFolderCache(realPath);

        return ResponseEntity.ok(Map.of(
                "message", "Folder created"
        ));
    }
    @PostMapping("/upload/init")
    public ResponseEntity<?> initUpload(
            @PathVariable String token,
            @RequestParam String fileName,
            @RequestParam long fileSize,
            @RequestParam long chunkSize,
            @RequestParam(defaultValue = "") String path,
            @RequestParam long lastModified
    ) throws IOException {

        ShareLink link = shareService.requireActive(token);

        if (fileSize <= 0 || chunkSize <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid fileSize or chunkSize"));
        }
        if (!link.getPermission().canUpload()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String realPath = shareService.resolveInsideShare(link, path);

        Path targetDir = fileService.resolveSafe(realPath);

        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
        }

        if (!Files.isDirectory(targetDir)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Target is not a directory"));
        }

        String safeFileName = Path.of(fileName).getFileName().toString();

        if (!StringUtils.hasText(safeFileName)
                || safeFileName.contains("..")
                || safeFileName.contains("/")
                || safeFileName.contains("\\")
                || safeFileName.contains("\0")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid file name"));
        }

        int totalChunks = (int) Math.ceil((double) fileSize / chunkSize);

        String uploadId = UUID.randomUUID().toString();

        UploadSessionDto meta = new UploadSessionDto();
        meta.setUploadId(uploadId);
        meta.setFileName(safeFileName);
        meta.setTargetPath(realPath);
        meta.setFileSize(fileSize);
        meta.setChunkSize(chunkSize);
        meta.setTotalChunks(totalChunks);
        meta.setUploadedChunks(new java.util.ArrayList<>());

        writeMeta(meta);

        return ResponseEntity.ok(meta);
    }
    @GetMapping("/upload/status")
    public ResponseEntity<?> uploadStatus(@RequestParam String uploadId) throws IOException {
        UploadSessionDto meta = readMeta(uploadId);

        if (meta == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(meta);
    }
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

        String safeFileName = Path.of(meta.getFileName()).getFileName().toString();

        if (!StringUtils.hasText(safeFileName)
                || !safeFileName.equals(meta.getFileName())
                || safeFileName.contains("..")
                || safeFileName.contains("/")
                || safeFileName.contains("\\")
                || safeFileName.contains("\0")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid file name"));
        }

        Path tempFile = getTempFile(uploadId);

        Path finalDir = fileService.resolveSafe(meta.getTargetPath());

        if (!Files.exists(finalDir)) {
            Files.createDirectories(finalDir);
        }

        if (!Files.isDirectory(finalDir)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Target is not a directory"));
        }

        Path finalPath = fileService.resolveSafe(
                meta.getTargetPath() + "/" + safeFileName
        );

        if (!Files.exists(tempFile)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Temp file not found"));
        }

        if (meta.getUploadedChunks().size() != meta.getTotalChunks()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Not all chunks uploaded"));
        }

        Files.move(tempFile, finalPath, StandardCopyOption.REPLACE_EXISTING);
        folderPrepareService.invalidateFolderCache(meta.getTargetPath());
        Files.deleteIfExists(getMetaFile(uploadId));

        uploadLocks.remove(uploadId);
        /*totalCacheService.rebuildStorageScanCacheAsync();*/

        return ResponseEntity.ok(Map.of("message", "Upload completed"));
    }
    private Path getUploadTempDir() throws IOException {
        Path dir = Path.of(appProperties.getUploadTempDir());
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
    @DeleteMapping("/clear-temp")
    public ResponseEntity<?> clearTemp(@PathVariable String token) throws IOException {
        ShareLink link = shareService.requireActive(token);

        if (!link.getPermission().canUpload()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        Path tempDir = getUploadTempDir();

        if (Files.exists(tempDir)) {
            try (var stream = Files.walk(tempDir)) {
                stream
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                if (!path.equals(tempDir)) {
                                    Files.deleteIfExists(path);
                                }
                            } catch (IOException e) {
                                System.out.println("Failed to delete temp file: " + path);
                            }
                        });
            }
        }

        return ResponseEntity.ok().build();
    }
    @GetMapping("/download-selected")
    public ResponseEntity<StreamingResponseBody> downloadSelected(
            @PathVariable String token,
            @RequestParam List<String> paths
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);

        if (!link.getPermission().canDownload()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        StreamingResponseBody body = outputStream -> {
            try (java.util.zip.ZipOutputStream zipOut =
                         new java.util.zip.ZipOutputStream(outputStream)) {

                for (String publicPath : paths) {
                    try {
                        String realPath = shareService.resolveInsideShare(link, publicPath);
                        Path source = fileService.resolveSafe(realPath);

                        if (!Files.exists(source)) {
                            continue;
                        }

                        if (Files.isDirectory(source)) {
                            try (java.util.stream.Stream<Path> walk = Files.walk(source)) {
                                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                                    Path relative = source.relativize(file);

                                    String entryName = source.getFileName().toString()
                                            + "/"
                                            + relative.toString().replace("\\", "/");

                                    zipOut.putNextEntry(new java.util.zip.ZipEntry(entryName));
                                    Files.copy(file, zipOut);
                                    zipOut.closeEntry();
                                }
                            }
                        } else {
                            zipOut.putNextEntry(
                                    new java.util.zip.ZipEntry(source.getFileName().toString())
                            );
                            Files.copy(source, zipOut);
                            zipOut.closeEntry();
                        }

                    } catch (Exception e) {
                        System.out.println("Skip shared zip item: " + publicPath + " / " + e.getMessage());
                    }
                }
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"selected-files.zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }
    @PostMapping("/prepare-folder")
    public Map<String, String> prepareFolder(
            @PathVariable String token,
            @RequestParam(defaultValue = "") String path,
            @RequestParam(defaultValue = "name") String sortField,
            @RequestParam(defaultValue = "asc") String sortDirection,
            @RequestParam(defaultValue = "all") String groupMode,
            @RequestParam(defaultValue = "false") boolean periodEnabled,
            @RequestParam(required = false) String periodFrom,
            @RequestParam(required = false) String periodTo
    ) throws IOException {

        ShareLink link = shareService.requireActive(token);

        String realPath = shareService.resolveInsideShare(link, path);

        String jobId = folderPrepareService.start(
                realPath,
                sortField,
                sortDirection,
                groupMode,
                periodEnabled,
                periodFrom,
                periodTo
        );

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
            @PathVariable String token,
            @RequestParam String jobId,
            @RequestParam int offset,
            @RequestParam int limit
    ) throws IOException {

        ShareLink link = shareService.requireActive(token);

        FolderPrepareJob job = folderPrepareService.get(jobId);

        if (job == null || !job.ready) {
            return Map.of("items", List.of());
        }

        int to = Math.min(offset + limit, job.items.size());

        List<FileItemDto> items =
                job.items.subList(offset, to)
                        .stream()
                        .map(item -> shareService.toPublicItem(link, item))
                        .toList();

        return Map.of(
                "items", items,
                "total", job.items.size()
        );
    }
}
