package ru.homeserver.photoshare.homeserver.music;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class MusicSourceService {
    public record Source(String id, String name, double duration, String audioUrl) {}
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final String FORMATS = "wav,mp3,flac,mov,ogg,aac,matroska,webm";
    private final Path root;
    private final ObjectMapper json;
    private final MusicProperties properties;
    private final Semaphore uploadSlot = new Semaphore(1);

    public MusicSourceService(MusicProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        root = Path.of(properties.getRoot()).toAbsolutePath().normalize().resolve("sources");
    }

    private Path folder(String id) {
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Некорректный номер образца"); }
        return root.resolve(id);
    }
    public Source get(String id) throws IOException { return json.readValue(folder(id).resolve("source.json").toFile(), Source.class); }
    public Path audio(String id) throws IOException {
        get(id);
        Path audio = folder(id).resolve("source.wav");
        if (!Files.isRegularFile(audio)) throw new NoSuchFileException(id);
        return audio;
    }

    public Source upload(String name, long length, InputStream input) throws IOException, InterruptedException {
        if (name == null || name.isBlank() || name.length() > 250) throw new IllegalArgumentException("Укажите имя аудиофайла");
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!Set.of("wav", "mp3", "flac", "m4a", "ogg", "opus", "aac", "webm").contains(extension))
            throw new IllegalArgumentException("Поддерживаются WAV, MP3, FLAC, M4A, OGG, OPUS, AAC и WEBM");
        if (length > MAX_BYTES) throw new IllegalArgumentException("Размер образца — не более 100 МБ");
        if (!uploadSlot.tryAcquire()) throw new IllegalStateException("Уже обрабатывается другой образец");
        Path dir = folder(UUID.randomUUID().toString());
        Path original = dir.resolve("original." + extension);
        try {
            Files.createDirectories(dir);
            try (OutputStream output = Files.newOutputStream(original, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[65536];
                long total = 0;
                int size;
                while ((size = input.read(buffer)) != -1) {
                    total += size;
                    if (total > MAX_BYTES) throw new IllegalArgumentException("Размер образца — не более 100 МБ");
                    output.write(buffer, 0, size);
                }
                if (total == 0) throw new IllegalArgumentException("Аудиофайл пуст");
            }
            Path probe = dir.resolve("probe.json");
            command(List.of(properties.getFfprobePath(), "-v", "error", "-protocol_whitelist", "file,pipe",
                    "-format_whitelist", FORMATS, "-show_entries", "format=duration:stream=codec_type", "-of", "json", original.toString()),
                    probe, dir.resolve("probe.log"), 30);
            var metadata = json.readTree(probe.toFile());
            boolean hasAudio = false;
            for (var stream : metadata.path("streams")) if ("audio".equals(stream.path("codec_type").asText())) hasAudio = true;
            double duration = metadata.path("format").path("duration").asDouble(0);
            if (!hasAudio || !Double.isFinite(duration) || duration < 10 || duration > 300)
                throw new IllegalArgumentException("Образец должен содержать аудио длительностью от 10 до 300 секунд");
            command(List.of(properties.getFfmpegPath(), "-nostdin", "-y", "-v", "error", "-protocol_whitelist", "file,pipe",
                    "-format_whitelist", FORMATS, "-i", original.toString(), "-map", "0:a:0", "-vn", "-t", "301",
                    "-ac", "2", "-ar", "48000", "-c:a", "pcm_s16le", "-threads", "2", dir.resolve("source.wav").toString()),
                    dir.resolve("convert.out"), dir.resolve("convert.log"), 90);
            // Measure the decoded duration as container metadata can be inaccurate.
            command(List.of(properties.getFfprobePath(), "-v", "error", "-show_entries", "format=duration", "-of", "json", dir.resolve("source.wav").toString()),
                    dir.resolve("decoded.json"), dir.resolve("decoded.log"), 15);
            duration = json.readTree(dir.resolve("decoded.json").toFile()).path("format").path("duration").asDouble(0);
            if (!Double.isFinite(duration) || duration < 10 || duration > 300)
                throw new IllegalArgumentException("Длительность декодированного образца должна быть от 10 до 300 секунд");
            String id = dir.getFileName().toString();
            Source source = new Source(id, name, duration, "/api/music/sources/" + id + "/audio");
            json.writeValue(dir.resolve("source.json").toFile(), source);
            return source;
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(original);
            Files.deleteIfExists(dir.resolve("source.wav"));
            throw e;
        } finally { uploadSlot.release(); }
    }

    private void command(List<String> args, Path output, Path error, int seconds) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(args).redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) throw new IllegalArgumentException("Обработка аудио заняла слишком много времени");
            if (process.exitValue() != 0) throw new IllegalArgumentException("Не удалось прочитать аудио. Проверьте формат и целостность файла");
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
