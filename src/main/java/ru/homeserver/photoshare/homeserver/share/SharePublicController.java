package ru.homeserver.photoshare.homeserver.share;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.homeserver.photoshare.homeserver.config.VideoPreviewProperties;
import ru.homeserver.photoshare.homeserver.dto.FileItemDto;
import ru.homeserver.photoshare.homeserver.service.FileService;
import ru.homeserver.photoshare.homeserver.service.FolderPrepareService;
import ru.homeserver.photoshare.homeserver.service.ThumbnailService;
import ru.homeserver.photoshare.homeserver.video.HlsConversionService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/share/{token}")
public class SharePublicController {

    private final ShareService shareService;
    private final FileService fileService;
    private final ThumbnailService thumbnailService;
    private final FolderPrepareService folderPrepareService;
    private final HlsConversionService hlsConversionService;
    private final VideoPreviewProperties videoPreviewProperties;

    public SharePublicController(
            ShareService shareService,
            FileService fileService,
            ThumbnailService thumbnailService,
            FolderPrepareService folderPrepareService, HlsConversionService hlsConversionService, VideoPreviewProperties videoPreviewProperties
    ) {
        this.shareService = shareService;
        this.fileService = fileService;
        this.thumbnailService = thumbnailService;
        this.folderPrepareService = folderPrepareService;
        this.hlsConversionService = hlsConversionService;
        this.videoPreviewProperties = videoPreviewProperties;
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
            @RequestParam(defaultValue = "100") int limit
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);

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
            @RequestParam(defaultValue = "") String path
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
            @RequestParam(defaultValue = "") String path
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);

        String realPath = shareService.resolveInsideShare(link, path);
        Path file = fileService.resolveSafe(realPath);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
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
            @RequestHeader HttpHeaders headers
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);

        String realPath = shareService.resolveInsideShare(link, path);
        Path file = fileService.resolveSafe(realPath);

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return ResponseEntity.notFound().build();
        }

        FileSystemResource video = new FileSystemResource(file);
        long fileSize = video.contentLength();

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
            @RequestParam("files") MultipartFile[] files
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);

        if (!link.getPermission().canUpload()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String realPath = shareService.resolveInsideShare(link, path);

        fileService.upload(realPath, files);
        folderPrepareService.invalidateFolderCache(realPath);

        return ResponseEntity.ok(Map.of("message", "Uploaded"));
    }

    @DeleteMapping
    public ResponseEntity<?> delete(
            @PathVariable String token,
            @RequestParam(defaultValue = "") String path
    ) throws IOException {
        ShareLink link = shareService.requireActive(token);

        if (!link.getPermission().canDelete()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String realPath = shareService.resolveInsideShare(link, path);

        fileService.delete(realPath);
        folderPrepareService.invalidateFolderCache(realPath);

        return ResponseEntity.ok(Map.of("message", "Deleted"));
    }
}
