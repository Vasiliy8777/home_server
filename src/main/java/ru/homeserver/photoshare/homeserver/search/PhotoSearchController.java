package ru.homeserver.photoshare.homeserver.search;

import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Map;

@RestController
@RequestMapping("/api/photo-search")
public class PhotoSearchController {
    private final PhotoSearchService search;

    public PhotoSearchController(PhotoSearchService search) { this.search = search; }

    @GetMapping("/status")
    public PhotoSearchService.Status status() { return search.status(); }

    @PostMapping("/index")
    public PhotoSearchService.Status start() { return search.start(); }

    @PostMapping("/stop")
    public PhotoSearchService.Status stop() { return search.stop(); }

    @GetMapping("/image")
    public ResponseEntity<FileSystemResource> image(@RequestParam String path, @RequestParam long version) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noCache())
                .body(new FileSystemResource(search.image(path, version)));
    }

    @GetMapping
    public PhotoSearchService.SearchPage search(@RequestParam String query,
                                               @RequestParam(defaultValue = "0") int offset,
                                               @RequestParam(defaultValue = "60") int limit) throws IOException, InterruptedException {
        return search.search(query, offset, limit);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchFileException.class)
    public ResponseEntity<?> missing(NoSuchFileException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Фото отсутствует в актуальном индексе"));
    }

    @ExceptionHandler({IOException.class, IllegalStateException.class})
    public ResponseEntity<?> unavailable(Exception e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InterruptedException.class)
    public ResponseEntity<?> interrupted() {
        Thread.currentThread().interrupt();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Поиск прерван"));
    }
}
