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
import java.util.Comparator;
import java.util.Set;
import java.time.LocalDate;
import java.time.ZoneId;
import ru.homeserver.photoshare.homeserver.dto.FileItemDto;
import ru.homeserver.photoshare.homeserver.share.ShareService;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/photo-search")
public class PhotoSearchController {
    private final PhotoSearchService search;
    private final ShareService shares;

    public PhotoSearchController(PhotoSearchService search) { this(search, null); }
    @Autowired
    public PhotoSearchController(PhotoSearchService search, ShareService shares) { this.search = search; this.shares = shares; }

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
                                               @RequestParam(defaultValue = "60") int limit,
                                               @RequestParam(defaultValue = "relevance") String sortField,
                                               @RequestParam(defaultValue = "asc") String sortDirection,
                                               @RequestParam(defaultValue = "all") String groupMode,
                                               @RequestParam(defaultValue = "false") boolean periodEnabled,
                                               @RequestParam(defaultValue = "") String periodFrom,
                                               @RequestParam(defaultValue = "") String periodTo) throws IOException, InterruptedException {
        if (!Set.of("relevance", "name", "size", "lastModified").contains(sortField)
                || !Set.of("asc", "desc").contains(sortDirection)
                || !Set.of("all", "photo", "video", "shared").contains(groupMode))
            throw new IllegalArgumentException("Недопустимые параметры фильтра");
        LocalDate from = date(periodEnabled ? periodFrom : "");
        LocalDate to = date(periodEnabled ? periodTo : "");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("Начало периода позже окончания");
        Set<String> shared = "shared".equals(groupMode) && shares != null ? shares.activeSharedPaths() : Set.of();
        Comparator<FileItemDto> order = switch (sortField) {
            case "size" -> Comparator.comparingLong(FileItemDto::size);
            case "lastModified" -> Comparator.comparingLong(FileItemDto::createdAt);
            case "name" -> Comparator.comparing(FileItemDto::name, String.CASE_INSENSITIVE_ORDER);
            default -> null;
        };
        if (order != null && "desc".equals(sortDirection)) order = order.reversed();
        return search.search(query, offset, limit, item -> {
            if ("photo".equals(groupMode) && !"image".equals(item.type())) return false;
            if ("video".equals(groupMode) && !"video".equals(item.type())) return false;
            if ("shared".equals(groupMode) && !shared.contains(item.relativePath())) return false;
            LocalDate day = java.time.Instant.ofEpochMilli(item.createdAt()).atZone(ZoneId.systemDefault()).toLocalDate();
            return (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to));
        }, order);
    }

    private static LocalDate date(String value) {
        try { return value.isBlank() ? null : LocalDate.parse(value); }
        catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("Некорректная дата"); }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchFileException.class)
    public ResponseEntity<?> missing(NoSuchFileException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Файл отсутствует в актуальном индексе"));
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
