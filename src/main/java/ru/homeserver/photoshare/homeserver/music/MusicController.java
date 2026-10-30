package ru.homeserver.photoshare.homeserver.music;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.io.FileNotFoundException;
import java.util.*;

@RestController
@RequestMapping("/api/music")
public class MusicController {
    private final MusicService music;
    private final MusicSourceService sources;
    public MusicController(MusicService music, MusicSourceService sources) { this.music = music; this.sources = sources; }
    @PostMapping(value = "/sources", consumes = {"application/octet-stream", "audio/*", "video/webm"})
    @ResponseStatus(HttpStatus.CREATED)
    public MusicSourceService.Source upload(@RequestParam String name, HttpServletRequest request) throws IOException, InterruptedException {
        return sources.upload(name, request.getContentLengthLong(), request.getInputStream());
    }
    @GetMapping("/sources/{id}") public MusicSourceService.Source source(@PathVariable String id) throws IOException { return sources.get(id); }
    @GetMapping("/sources/{id}/audio") public ResponseEntity<FileSystemResource> sourceAudio(@PathVariable String id) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(sources.audio(id)));
    }
    @GetMapping("/status") public Map<String, Object> status() { return music.status(); }
    @GetMapping("/jobs") public List<MusicService.Job> jobs() throws IOException { return music.history(); }
    @GetMapping("/jobs/{id}") public MusicService.Job job(@PathVariable String id) throws IOException { return music.get(id); }
    @PostMapping("/jobs") @ResponseStatus(HttpStatus.ACCEPTED)
    public MusicService.Job start(@RequestBody MusicRequest request) throws IOException { return music.generate(request); }
    @PostMapping("/lyrics") @ResponseStatus(HttpStatus.ACCEPTED)
    public MusicService.Job lyrics(@RequestBody MusicService.LyricsRequest request) throws IOException { return music.generateLyrics(request); }
    @PostMapping("/jobs/{id}/save") public MusicService.Job save(@PathVariable String id) throws IOException { return music.save(id); }
    @DeleteMapping("/jobs/{id}") public MusicService.Job delete(@PathVariable String id) throws IOException { return music.delete(id); }
    @GetMapping("/jobs/{id}/audio")
    public ResponseEntity<FileSystemResource> audio(@PathVariable String id) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
                .cacheControl(CacheControl.noStore()).body(new FileSystemResource(music.audio(id)));
    }
    @GetMapping("/jobs/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String id) throws IOException {
        var audio = new FileSystemResource(music.audio(id));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("song-" + id + ".wav").build().toString())
                .cacheControl(CacheControl.noStore()).body(audio);
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }
    @ExceptionHandler({NoSuchFileException.class, FileNotFoundException.class}) public ResponseEntity<?> missing() {
        return ResponseEntity.status(404).body(Map.of("error", "Задание или аудиофайл не найден"));
    }
    @ExceptionHandler(IOException.class) public ResponseEntity<?> unavailable(IOException e) {
        return ResponseEntity.status(503).body(Map.of("error", "Не удалось прочитать или сохранить файлы muz_gen"));
    }
    @ExceptionHandler(InterruptedException.class) public ResponseEntity<?> interrupted() {
        Thread.currentThread().interrupt();
        return ResponseEntity.status(503).body(Map.of("error", "Обработка аудио прервана"));
    }
}
