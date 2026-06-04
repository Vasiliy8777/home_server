package ru.homeserver.photoshare.homeserver.video;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.homeserver.photoshare.homeserver.config.VideoPreviewProperties;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/video/hls/files")
public class HlsFileController {

    private final VideoPreviewProperties props;

    public HlsFileController(VideoPreviewProperties props) {
        this.props = props;
    }

    @GetMapping("/{key}/{filename}")
    public ResponseEntity<Resource> getFile(
            @PathVariable String key,
            @PathVariable String filename
    ) throws Exception {

        Path folder = props.getHlsCacheDir().resolve(key).normalize();
        Path file = folder.resolve(filename).normalize();

        if (!file.startsWith(folder) || !Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }

        MediaType mediaType = detectMediaType(filename);

        /*return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS))
                .body(new FileSystemResource(file));*/
        FileSystemResource resource = new FileSystemResource(file);

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL,
                        "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .contentType(mediaType)
                .body(resource);
    }

    private MediaType detectMediaType(String filename) {
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
}