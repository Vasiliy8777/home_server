package ru.homeserver.photoshare.homeserver.music;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class MusicService {
    public record Job(String id, String kind, String state, String message, String createdAt,
                      MusicRequest request, String lyrics, String savedFile, String aceTaskId) {}
    public record LyricsRequest(String prompt, String description, String language) {}
    private final Path root;
    private final ObjectMapper json;
    private final LocalMusicClient client;
    private final MusicProperties properties;
    private final MusicSourceService sources;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("muz-gen").factory());

    public MusicService(MusicProperties properties, ObjectMapper json, LocalMusicClient client) {
        this(properties, json, client, new MusicSourceService(properties, json));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public MusicService(MusicProperties properties, ObjectMapper json, LocalMusicClient client, MusicSourceService sources) {
        root = Path.of(properties.getRoot()).toAbsolutePath().normalize();
        this.properties = properties;
        this.json = json;
        this.client = client;
        this.sources = sources;
    }

    private void directories() throws IOException {
        for (String name : List.of("jobs", "tmp", "lyrics-prompts", "lyrics", "music-prompts", "logs"))
            Files.createDirectories(root.resolve(name));
    }

    private Path jobFile(String id) { return root.resolve("jobs").resolve(validId(id) + ".json"); }
    private String validId(String id) {
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Некорректный номер задания"); }
        return id;
    }
    private Path temp(String id) { return root.resolve("tmp").resolve(validId(id)).resolve("result.wav"); }

    private synchronized Job write(Job job) throws IOException {
        directories();
        Path target = jobFile(job.id());
        Path pending = target.resolveSibling(target.getFileName() + ".tmp");
        json.writeValue(pending.toFile(), job);
        try { Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING); }
        return job;
    }

    public synchronized Job get(String id) throws IOException {
        Job job = json.readValue(jobFile(id).toFile(), Job.class);
        // Do not leave a job permanently "running" after a server restart.
        if (!busy.get() && Set.of("queued", "running").contains(job.state()))
            job = write(change(job, "failed", "Сервер был перезапущен. Создайте новое задание", job.lyrics(), job.savedFile(), job.aceTaskId()));
        return job;
    }

    public synchronized List<Job> history() throws IOException {
        if (!Files.isDirectory(root.resolve("jobs"))) return List.of();
        try (var files = Files.list(root.resolve("jobs"))) {
            List<Job> jobs = new ArrayList<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                try { jobs.add(get(file.getFileName().toString().replace(".json", ""))); }
                catch (IOException | IllegalArgumentException ignored) { /* A damaged record must not hide other results. */ }
            }
            return jobs.stream().sorted(Comparator.comparing(Job::createdAt).reversed()).limit(50).toList();
        }
    }

    public Map<String, Object> status() {
        return Map.of("available", client.available(), "busy", busy.get(), "root", root.toString(),
                "lyricsModel", properties.getLyricsModel(), "autoStart", properties.isAutoStart());
    }

    public Job generate(MusicRequest request) throws IOException {
        Path sourceAudio = null;
        if (!"text2music".equals(request.taskType())) {
            var source = sources.get(request.sourceId());
            sourceAudio = sources.audio(request.sourceId());
            if ("repaint".equals(request.taskType()) && request.repaintEnd() > source.duration())
                throw new IllegalArgumentException("Конец изменяемого фрагмента выходит за пределы образца");
        }
        final Path source = sourceAudio;
        return start("music", request, id -> {
            Files.writeString(root.resolve("music-prompts").resolve(id + ".txt"), request.description());
            if (!request.changes().isEmpty()) Files.writeString(root.resolve("music-prompts").resolve(id + "-changes.txt"), request.changes());
            Files.writeString(root.resolve("lyrics").resolve(id + "-input.txt"), request.lyrics());
            update(id, "Освобождаем память Ollama", "");
            client.unloadOllama();
            update(id, "Подготавливаем музыкальный генератор", "");
            ensureEngine();
            String aceId;
            if (source == null) aceId = client.submit(request);
            else {
                // ACE-Step accepts absolute input paths only inside its configured temp directory.
                Path staged = stagedSource(id);
                Files.createDirectories(staged.getParent());
                Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
                aceId = client.submit(request, staged);
            }
            Job job = get(id);
            write(change(job, "running", "Создаём музыку. Это может занять несколько минут", "", "", aceId));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.getTimeoutSeconds());
            JsonNode entry;
            while (true) {
                if (System.nanoTime() > deadline) throw new IOException("Истекло время ожидания ACE-Step; проверьте журнал движка перед повторным запуском");
                entry = client.result(aceId);
                int state = entry.path("status").asInt(0);
                if (state == 2) {
                    String detail = entry.path("error").asText("Не удалось создать аудио. Проверьте журнал ACE-Step");
                    if (detail.toLowerCase(Locale.ROOT).contains("out of memory"))
                        detail = "Недостаточно видеопамяти. Освободите GPU или выберите модель Turbo";
                    if (detail.length() > 1000) detail = detail.substring(0, 1000);
                    throw new IOException(detail);
                }
                if (state == 1) break;
                Thread.sleep(2500);
            }
            JsonNode result = entry.path("result");
            if (result.isTextual()) result = json.readTree(result.asText());
            if (!result.isArray() || result.isEmpty()) throw new IOException("ACE-Step не вернул результат");
            Path work = temp(id).getParent();
            Files.createDirectories(work);
            json.writeValue(work.resolve("ace-result.json").toFile(), result);
            client.download(result.get(0).path("file").asText(), work.resolve("result.part"));
            Files.move(work.resolve("result.part"), temp(id), StandardCopyOption.REPLACE_EXISTING);
            write(change(get(id), "ready", "Готово. Прослушайте и сохраните результат", "", "", aceId));
        });
    }

    private void ensureEngine() throws IOException, InterruptedException {
        if (!properties.isAutoStart() || client.available()) return;
        Path launcher = root.resolve("runtime/start-music.ps1");
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows") || !Files.isRegularFile(launcher))
            throw new IOException("ACE-Step не запущен. Выполните установку scripts/setup-music.ps1");
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                launcher.toString(), "-Root", root.toString()).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve("logs/engine-launch.log").toFile())).start();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        while (!client.available()) {
            if (!process.isAlive()) throw new IOException("Не удалось запустить ACE-Step. Проверьте logs/engine-launch.log");
            if (System.nanoTime() > deadline) throw new IOException("ACE-Step запускается слишком долго. Проверьте logs/engine-launch.log");
            Thread.sleep(1500);
        }
    }

    @FunctionalInterface private interface Work { void run(String id) throws Exception; }

    public Job generateLyrics(LyricsRequest r) throws IOException {
        String prompt = r.prompt() == null ? "" : r.prompt().strip();
        String description = r.description() == null ? "" : r.description().strip();
        if (prompt.isEmpty() || prompt.length() > 16000 || description.length() > 4000
                || !Set.of("ru", "en", "zh", "ja", "ko", "de", "fr", "es", "pt", "it").contains(r.language() == null ? "" : r.language()))
            throw new IllegalArgumentException("Введите запрос для текста и выберите язык");
        return start("lyrics", null, id -> {
            Files.writeString(root.resolve("lyrics-prompts").resolve(id + ".txt"), prompt);
            Files.writeString(root.resolve("music-prompts").resolve(id + ".txt"), description);
            update(id, "Пишем текст песни", "");
            String text = client.lyrics(prompt, description, r.language());
            Files.writeString(root.resolve("lyrics").resolve(id + ".txt"), text);
            write(change(get(id), "ready", "Текст песни готов", text, "", ""));
        });
    }

    private synchronized Job start(String kind, MusicRequest request, Work work) throws IOException {
        if (!busy.compareAndSet(false, true)) throw new IllegalStateException("Уже выполняется генерация. Дождитесь её окончания");
        try {
            String id = UUID.randomUUID().toString();
            Job job = write(new Job(id, kind, "queued", "Подготовка", Instant.now().toString(), request, "", "", ""));
            worker.submit(() -> {
                try { work.run(id); }
                catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    try { write(change(get(id), "failed", "Ошибка: " + Objects.toString(e.getMessage(), e.getClass().getSimpleName()), "", "", get(id).aceTaskId())); }
                    catch (IOException failure) { org.slf4j.LoggerFactory.getLogger(MusicService.class).error("Cannot persist music failure {}", id, failure); }
                } finally { busy.set(false); }
            });
            return job;
        } catch (IOException | RuntimeException e) { busy.set(false); throw e; }
    }

    private void update(String id, String message, String lyrics) throws IOException {
        Job job = get(id);
        write(change(job, "running", message, lyrics, job.savedFile(), job.aceTaskId()));
    }
    private Job change(Job j, String state, String message, String lyrics, String saved, String aceId) {
        return new Job(j.id(), j.kind(), state, message, j.createdAt(), j.request(), lyrics, saved, aceId);
    }

    public synchronized Job save(String id) throws IOException {
        Job job = get(id);
        if ("saved".equals(job.state())) return job;
        if (!"music".equals(job.kind()) || !"ready".equals(job.state())) throw new IllegalStateException("Нет готового временного результата");
        String name = "song-" + id + ".wav";
        Path destination = root.resolve(name);
        Files.move(temp(id), destination, StandardCopyOption.REPLACE_EXISTING);
        Job saved;
        try { saved = write(change(job, "saved", "Сохранено: " + destination, "", name, job.aceTaskId())); }
        catch (IOException e) { Files.move(destination, temp(id), StandardCopyOption.REPLACE_EXISTING); throw e; }
        try { removeEngineCopy(id); }
        catch (IOException e) { org.slf4j.LoggerFactory.getLogger(MusicService.class).warn("Cannot remove temporary ACE audio {}", id, e); }
        return saved;
    }

    public synchronized Job delete(String id) throws IOException {
        Job job = get(id);
        if ("deleted".equals(job.state())) return job;
        if (!"music".equals(job.kind()) || !Set.of("ready", "failed").contains(job.state()))
            throw new IllegalStateException("Можно удалить только временный результат");
        removeEngineCopy(id);
        Files.deleteIfExists(temp(id));
        Files.deleteIfExists(temp(id).resolveSibling("result.part"));
        return write(change(job, "deleted", "Временный результат удалён. Промпты сохранены", "", "", job.aceTaskId()));
    }

    private void removeEngineCopy(String id) throws IOException {
        Files.deleteIfExists(stagedSource(id));
        Path metadata = temp(id).resolveSibling("ace-result.json");
        if (!Files.isRegularFile(metadata)) return;
        JsonNode result = json.readTree(metadata.toFile());
        if (result.isArray()) for (JsonNode audio : result) client.removeTemporarySource(audio.path("file").asText());
    }

    private Path stagedSource(String id) { return root.resolve("tmp/ace-step/cloud-inputs").resolve(validId(id) + ".wav"); }

    public synchronized Path audio(String id) throws IOException {
        Job job = get(id);
        if (!"music".equals(job.kind()) || !Set.of("ready", "saved").contains(job.state())) throw new NoSuchFileException(id);
        Path path = "saved".equals(job.state()) ? root.resolve("song-" + validId(id) + ".wav") : temp(id);
        if (!Files.isRegularFile(path)) throw new NoSuchFileException(id);
        return path;
    }

    @PreDestroy public void close() { worker.shutdownNow(); }
}
