package ru.homeserver.photoshare.homeserver.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import ru.homeserver.photoshare.homeserver.dto.FileItemDto;
import ru.homeserver.photoshare.homeserver.service.FileService;
import ru.homeserver.photoshare.homeserver.service.SqliteCacheStore;
import ru.homeserver.photoshare.homeserver.util.HiddenPaths;
import ru.homeserver.photoshare.homeserver.util.PhotoFormats;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

@Service
public class PhotoSearchService {
    private static final Logger log = LoggerFactory.getLogger(PhotoSearchService.class);
    private final FileService files;
    private final PhotoSearchProperties properties;
    private final PhotoAiClient ai;
    private final PhotoImageReader images;
    private final Path indexDir;
    private final boolean video;
    private final SqliteCacheStore database;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "photo-search-index");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Thread indexThread;
    private volatile boolean stopRequested;
    private volatile boolean closing;
    private boolean running;
    private int scanned, indexed, unchanged, failed, processed;
    private String stage = "Индекс ещё не обновлялся", currentPath = "", error;

    public PhotoSearchService(FileService files, AppProperties app, PhotoSearchProperties properties,
                              PhotoAiClient ai, PhotoImageReader images) throws IOException {
        this(files, app, properties, ai, images, null);
    }

    @Autowired
    public PhotoSearchService(FileService files, AppProperties app, PhotoSearchProperties properties,
                              PhotoAiClient ai, PhotoImageReader images, SqliteCacheStore database) throws IOException {
        this(files, app, properties, ai, images, database, false);
    }

    PhotoSearchService(FileService files, AppProperties app, PhotoSearchProperties properties,
                       PhotoAiClient ai, PhotoImageReader images, SqliteCacheStore database, boolean video) throws IOException {
        this.video = video;
        this.files = files;
        this.properties = properties;
        this.ai = ai;
        this.images = images;
        this.database = database != null && database.enabled() ? database : null;
        // Separate indexes when the same cache location serves different storage roots.
        indexDir = Path.of(app.getMetadataCacheDir()).resolve(video ? "video-search" : "photo-search")
                .resolve(hash(files.getRootPath().toString()));
        Files.createDirectories(indexDir);
        if (this.database != null) {
            for (Entry entry : this.database.photos(indexDir.toString())) {
                try { loadEntry(entry); }
                catch (IOException | IllegalArgumentException invalid) {
                    log.warn("INDEX_ENTRY_INVALID path={} reason={}", entry.relativePath(), invalid.toString());
                }
            }
        }
        if (this.database == null || !this.database.photosImported(indexDir.toString())) {
        try (var paths = Files.list(indexDir)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                Entry imported = null;
                try {
                    Entry entry = mapper.readValue(path.toFile(), Entry.class);
                    if (!entries.containsKey(entry.relativePath()) && loadEntry(entry) && this.database != null) {
                        imported = entries.get(entry.relativePath());
                    }
                } catch (IOException | IllegalArgumentException ignored) {
                    log.warn("INDEX_LOAD_FAILED cacheFile={} reason={}", path, ignored.toString());
                    // A damaged/obsolete entry will be rebuilt on the next index run.
                }
                // A database failure must not be mistaken for a damaged JSON or mark migration complete.
                if (imported != null) this.database.savePhoto(indexDir.toString(), imported);
            }
        }
        if (this.database != null) this.database.markPhotosImported(indexDir.toString());
        }
        log.info("INDEX_LOADED directory={} entries={}", indexDir, entries.size());
    }

    private boolean loadEntry(Entry entry) throws IOException {
        Path photo = resolveEntry(entry.relativePath());
        if (photo == null || !ai.signature().equals(entry.signature()) || entry.description() == null || entry.description().isBlank()) return false;
        entries.put(entry.relativePath(), new Entry(entry.relativePath(), entry.size(), entry.modified(), entry.signature(),
                entry.description(), normalize(entry.vector())));
        return true;
    }

    public synchronized Status status() {
        return new Status(properties.isEnabled(), running, entries.size(), scanned, indexed, unchanged,
                failed, stage, currentPath, error, properties.getVisionModel(), properties.getEmbeddingModel(), processed);
    }

    @PostConstruct
    public void resumeInterruptedRun() {
        if (properties.isEnabled() && properties.isAutoResume() && Files.exists(runMarker())) start();
    }
    private Path runMarker() { return indexDir.resolve("index.running"); }
    public synchronized Status start() {
        requireEnabled();
        if (closing) throw new IllegalStateException("Server is shutting down");
        if (running) return status();
        try { Files.writeString(runMarker(), "running"); }
        catch (IOException e) { throw new IllegalStateException("Cannot save index run marker", e); }
        running = true;
        stopRequested = false;
        scanned = indexed = unchanged = failed = processed = 0;
        error = null;
        currentPath = "";
        stage = video ? "Обход видео" : "Обход фотографий";
        log.info("INDEX_START root={} visionModel={} embeddingModel={} available={} mode={}",
                files.getRootPath(), properties.getVisionModel(), properties.getEmbeddingModel(), entries.size(), video ? "video" : "photo");
        worker.submit(this::indexPhotos);
        return status();
    }

    public synchronized Status stop() {
        if (running) {
            stopRequested = true;
            try { Files.deleteIfExists(runMarker()); }
            catch (IOException e) { log.warn("Cannot clear manually stopped index marker", e); }
            stage = "Остановка индексации";
            log.info("INDEX_STOP_REQUESTED currentPath={}", currentPath);
            if (indexThread != null) indexThread.interrupt();
        }
        return status();
    }

    private void indexPhotos() {
        indexThread = Thread.currentThread();
        long started = System.nanoTime();
        Map<String, Integer> skippedFormats = new TreeMap<>();
        Map<String, Integer> photoFormats = new TreeMap<>();
        try {
            Files.walkFileTree(files.getRootPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (stopRequested) return FileVisitResult.TERMINATE;
                    if (excluded(dir)) {
                        log.debug("SKIP_DIRECTORY path={}", files.toRelative(dir));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    try {
                        if (!safeRealPath(dir)) return FileVisitResult.SKIP_SUBTREE;
                    } catch (IOException failure) {
                        recordFailure(files.toRelative(dir), "read-directory", failure);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path photo, BasicFileAttributes attrs) throws IOException {
                    if (stopRequested) return FileVisitResult.TERMINATE;
                    if (attrs.isRegularFile() && !excluded(photo)) {
                        String ext = PhotoFormats.extension(photo);
                        boolean safe = false;
                        if (isPhoto(photo)) {
                            try { safe = safeRealPath(photo); }
                            catch (IOException failure) {
                                recordFailure(files.toRelative(photo), "read-path", failure);
                                return FileVisitResult.CONTINUE;
                            }
                        }
                        if (safe) {
                            photoFormats.merge(ext, 1, Integer::sum);
                            indexPhoto(photo, attrs);
                        } else {
                            skippedFormats.merge(ext, 1, Integer::sum);
                            log.debug("SKIP_FILE path={} extension={} reason=not-photo-or-outside-root", files.toRelative(photo), ext);
                        }
                    }
                    return stopRequested ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path path, IOException exc) {
                    recordFailure(files.toRelative(path), "read-path", exc);
                    return stopRequested ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }
            });
            if (!stopRequested && failed == 0) pruneRemoved();
            synchronized (this) {
                stage = stopRequested ? "Индексация остановлена" : failed > 0 ? "Индексация завершена с ошибками" : "Индексация завершена";
            }
        } catch (Exception e) {
            log.error("INDEX_ABORTED path={} reason={}", currentPath, e.toString(), e);
            synchronized (this) {
                error = e.getMessage();
                stage = "Ошибка индексации";
            }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                if (!closing) {
                    try { Files.deleteIfExists(runMarker()); }
                    catch (IOException e) { log.warn("Cannot clear index run marker", e); }
                }
                running = false;
                currentPath = "";
                indexThread = null;
                log.info("INDEX_FINISHED stage={} scanned={} indexed={} unchanged={} failed={} available={} elapsedMs={} photoFormats={} skippedFileExtensions={}",
                        stage, scanned, indexed, unchanged, failed, entries.size(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), photoFormats, skippedFormats);
            }
        }
    }

    private void indexPhoto(Path photo, BasicFileAttributes attrs) throws IOException {
        String relative = files.toRelative(photo);
        long started = System.nanoTime();
        String processingStage = "convert";
        synchronized (this) { scanned++; currentPath = relative; }
        Entry old = entries.get(relative);
        if (old != null && matches(old, attrs) && validImage(old)) {
            synchronized (this) { unchanged++; processed++; }
            log.info("UNCHANGED path={} extension={} size={} modified={} reason=matching-size-time-model-and-preview",
                    relative, PhotoFormats.extension(photo), attrs.size(), attrs.lastModifiedTime());
            return;
        }
        log.info("PHOTO_START path={} extension={} size={}", relative, PhotoFormats.extension(photo), attrs.size());
        try {
            byte[] jpeg = images.readJpeg(photo);
            if (stopRequested) return;
            processingStage = "describe";
            Path checkpoint = indexDir.resolve(hash(relative) + ".pending");
            Pending pending = null;
            if (Files.exists(checkpoint)) {
                try { pending = mapper.readValue(checkpoint.toFile(), Pending.class); }
                catch (IOException invalid) { log.warn("Ignoring damaged checkpoint {}", checkpoint); }
            }
            String description;
            if (pending != null && pending.size() == attrs.size()
                    && pending.modified() == attrs.lastModifiedTime().toMillis()
                    && ai.signature().equals(pending.signature()) && pending.description() != null && !pending.description().isBlank()) {
                description = pending.description();
            } else {
                description = retryAi(() -> ai.describe(jpeg));
                if (description == null || description.isBlank()) throw new IOException("Empty description");
                writeJson(checkpoint, new Pending(attrs.size(), attrs.lastModifiedTime().toMillis(), ai.signature(), description));
            }
            if (description == null || description.isBlank()) throw new IOException("Пустое описание фотографии");
            if (stopRequested) return;
            processingStage = "embed";
            float[] vector = normalize(retryAi(() -> ai.embed(description, false)));
            if (stopRequested) return;
            // Do not associate a description with a file modified during analysis.
            processingStage = "verify-file";
            BasicFileAttributes after = Files.readAttributes(photo, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || after.size() != attrs.size()
                    || !after.lastModifiedTime().equals(attrs.lastModifiedTime()) || !safeRealPath(photo)) {
                throw new IOException("Фото изменилось во время анализа; обновите индекс ещё раз");
            }
            Entry entry = new Entry(relative, attrs.size(), attrs.lastModifiedTime().toMillis(),
                    ai.signature(), description, vector);
            processingStage = "save-index";
            saveImage(entry, jpeg);
            save(entry);
            entries.put(relative, entry);
            try { Files.deleteIfExists(checkpoint); }
            catch (IOException e) { log.warn("Cannot clear checkpoint {}", checkpoint, e); }
            synchronized (this) { indexed++; processed++; }
            log.info("PHOTO_INDEXED path={} jpegBytes={} descriptionChars={} vectorDimensions={} elapsedMs={}",
                    relative, jpeg.length, description.length(), vector.length,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (MediaSkippedException e) {
            synchronized (this) { processed++; }
            log.info("MEDIA_SKIPPED path={} reason={}", relative, e.getMessage());
        } catch (InterruptedException e) {
            log.info("PHOTO_INTERRUPTED path={} stage={}", relative, processingStage);
            Thread.currentThread().interrupt();
            stopRequested = true;
        } catch (PhotoAiClient.UnavailableException e) {
            synchronized (this) { processed++; }
            recordFailure(relative, processingStage, e);
            throw e;
        } catch (Exception e) {
            synchronized (this) { processed++; }
            recordFailure(relative, processingStage, e);
        }
    }

    private synchronized void recordFailure(String path, String processingStage, Exception failure) {
        failed++;
        String reason = String.valueOf(failure.getMessage());
        if (reason.length() > 500) reason = reason.substring(0, 500) + "… Подробности в photo-search.log";
        error = path + " [" + processingStage + "]: " + reason;
        log.warn("PHOTO_FAILED path={} extension={} stage={} reason={}", path,
                PhotoFormats.extension(Path.of(path)), processingStage, failure.toString(), failure);
    }

    private void pruneRemoved() throws IOException {
        for (Entry entry : List.copyOf(entries.values())) {
            if (stopRequested) return;
            Path path = resolveEntry(entry.relativePath());
            if (path == null || Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                entries.remove(entry.relativePath());
                Files.deleteIfExists(entryFile(entry.relativePath()));
                Files.deleteIfExists(imageFile(entry));
                Files.deleteIfExists(imageDigestFile(entry));
                if (database != null) database.removePhoto(indexDir.toString(), entry.relativePath());
                log.info("INDEX_REMOVED path={} reason=missing-or-excluded", entry.relativePath());
            }
        }
    }

    public SearchPage search(String query, int offset, int limit) throws IOException, InterruptedException {
        return search(query, offset, limit, item -> true, null);
    }

    public SearchPage search(String query, int offset, int limit,
                             java.util.function.Predicate<FileItemDto> filter,
                             Comparator<FileItemDto> order) throws IOException, InterruptedException {
        requireEnabled();
        if (query == null || query.isBlank() || query.length() > 1000) {
            throw new IllegalArgumentException("Введите запрос длиной от 1 до 1000 символов");
        }
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Недопустимые параметры страницы");
        }
        if (entries.isEmpty()) return new SearchPage(List.of(), 0, status());
        float[] vector = normalize(ai.embed(query.strip(), true));
        Comparator<Match> bestFirst = Comparator.comparingDouble(Match::score).reversed()
                .thenComparing(m -> m.entry().relativePath());
        if (order != null) bestFirst = Comparator.comparing((Match m) -> toItem(m.entry()), order)
                .thenComparing(bestFirst);
        PriorityQueue<Match> best = new PriorityQueue<>(bestFirst.reversed());
        int total = 0;
        for (Entry entry : entries.values()) {
            if (!ai.signature().equals(entry.signature()) || vector.length != entry.vector().length) continue;
            double score = similarity(vector, entry.vector());
            if (score < properties.getMinScore()) continue;
            if (!filter.test(toItem(entry))) continue;
            Path photo = resolveEntry(entry.relativePath());
            if (photo == null) continue;
            try {
                BasicFileAttributes attrs = Files.readAttributes(photo, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isRegularFile() || !matches(entry, attrs) || !safeRealPath(photo)) continue;
            } catch (IOException e) { continue; }
            total++;
            best.add(new Match(entry, score));
            if (best.size() > offset + limit) best.poll();
        }
        List<SearchHit> hits = best.stream().sorted(bestFirst).skip(offset)
                .map(m -> new SearchHit(toItem(m.entry()), m.entry().description(), m.score())).toList();
        return new SearchPage(hits, total, status());
    }

    private FileItemDto toItem(Entry entry) {
        String encoded = URLEncoder.encode(entry.relativePath(), StandardCharsets.UTF_8).replace("+", "%20");
        String thumbnail = video ? "/api/files/video-thumbnail?path=" + encoded
                : "/api/photo-search/image?path=" + encoded + "&version=" + entry.modified();
        String lower = entry.relativePath().toLowerCase(Locale.ROOT);
        String preview = video || lower.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|avif)$")
                ? "/api/files/raw?path=" + encoded : thumbnail;
        return new FileItemDto(Path.of(entry.relativePath()).getFileName().toString(), entry.relativePath(), false,
                entry.size(), video ? "video" : "image", preview, thumbnail, "/api/files/download?path=" + encoded,
                entry.modified(), entry.modified(), null, null, false, null, null);
    }

    private boolean matches(Entry entry, BasicFileAttributes attrs) {
        return entry.size() == attrs.size() && entry.modified() == attrs.lastModifiedTime().toMillis()
                && ai.signature().equals(entry.signature());
    }

    private Path resolveEntry(String relative) {
        if (relative == null || relative.isBlank()) return null;
        try {
            Path path = files.resolveSafe(relative);
            return !excluded(path) && isPhoto(path) && relative.equals(files.toRelative(path)) ? path : null;
        } catch (IllegalArgumentException e) { return null; }
    }

    private boolean excluded(Path path) {
        return HiddenPaths.shouldSkip(path) || path.toAbsolutePath().normalize().startsWith(indexDir);
    }

    private boolean safeRealPath(Path path) throws IOException {
        Path real = path.toRealPath();
        return real.startsWith(files.getRootPath().toRealPath()) && !HiddenPaths.shouldSkip(real);
    }

    private boolean isPhoto(Path path) {
        return video ? Set.of("mp4", "mov", "avi", "mkv", "webm", "m4v", "ogv", "insv", "lrv")
                .contains(PhotoFormats.extension(path)) : PhotoFormats.isPhoto(path);
    }

    private Path entryFile(String relative) { return indexDir.resolve(hash(relative) + ".json"); }

    private Path imageFile(Entry entry) {
        return indexDir.resolve(hash(entry.relativePath() + "|" + entry.size() + "|" + entry.modified() + "|" + entry.signature()) + ".jpg");
    }
    private Path imageDigestFile(Entry entry) { return Path.of(imageFile(entry) + ".sha256"); }
    private boolean validImage(Entry entry) {
        try {
            Path image = imageFile(entry);
            if (!Files.isRegularFile(image)) {
                Path legacy = indexDir.resolve(hash(entry.relativePath()) + ".jpg");
                if (!Files.isRegularFile(legacy) || Files.size(legacy) == 0) return false;
                saveImage(entry, Files.readAllBytes(legacy));
            }
            String expected = mapper.readValue(imageDigestFile(entry).toFile(), String.class);
            return expected.equals(HexFormat.of().formatHex(digest(Files.readAllBytes(image))));
        } catch (IOException e) { return false; }
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private void writeJson(Path target, Object value) throws IOException {
        Path temp = Files.createTempFile(indexDir, "checkpoint-", ".tmp");
        try { mapper.writeValue(temp.toFile(), value); replace(temp, target); }
        finally { Files.deleteIfExists(temp); }
    }
    private record Pending(long size, long modified, String signature, String description) {}
    @FunctionalInterface private interface AiOperation<T> { T run() throws IOException, InterruptedException; }
    private <T> T retryAi(AiOperation<T> operation) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            if (stopRequested) throw new InterruptedException("Index stopped");
            try {
                T result = operation.run();
                synchronized (this) { stage = video ? "Обход видео" : "Обход фотографий"; }
                return result;
            }
            catch (PhotoAiClient.TemporaryException e) {
                if (attempt >= Math.max(1, properties.getRetryAttempts())) throw e;
                synchronized (this) { stage = "Ожидание Ollama: повтор " + attempt; }
                log.warn("OLLAMA_RETRY attempt={} path={} reason={}", attempt, currentPath, e.toString());
                TimeUnit.SECONDS.sleep(Math.max(1, properties.getRetryDelaySeconds()));
            }
        }
    }

    public Path image(String relative, long version) throws IOException {
        requireEnabled();
        Entry entry = entries.get(relative);
        Path photo = resolveEntry(relative);
        if (entry == null || photo == null || version != entry.modified() || !safeRealPath(photo)) {
            throw new NoSuchFileException("Фото отсутствует в актуальном индексе");
        }
        BasicFileAttributes attrs = Files.readAttributes(photo, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || !matches(entry, attrs) || !validImage(entry)) {
            throw new NoSuchFileException("Обновите индекс фотографий");
        }
        return imageFile(entry);
    }

    private void saveImage(Entry entry, byte[] jpeg) throws IOException {
        Path temp = Files.createTempFile(indexDir, "image-", ".tmp");
        try {
            Files.write(temp, jpeg);
            replace(temp, imageFile(entry));
            writeJson(imageDigestFile(entry), HexFormat.of().formatHex(digest(jpeg)));
        } finally { Files.deleteIfExists(temp); }
    }

    private void save(Entry entry) throws IOException {
        if (database != null) { database.savePhoto(indexDir.toString(), entry); return; }
        Path temp = Files.createTempFile(indexDir, "entry-", ".tmp");
        try {
            mapper.writeValue(temp.toFile(), entry);
            replace(temp, entryFile(entry.relativePath()));
        } finally { Files.deleteIfExists(temp); }
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static float[] normalize(float[] vector) throws IOException {
        if (vector == null || vector.length == 0) throw new IOException("Пустой вектор поиска");
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IOException("Некорректный вектор поиска");
            norm += (double) value * value;
        }
        if (norm == 0) throw new IOException("Нулевой вектор поиска");
        float[] result = vector.clone();
        double length = Math.sqrt(norm);
        for (int i = 0; i < result.length; i++) result[i] = (float) (result[i] / length);
        return result;
    }

    private static double similarity(float[] a, float[] b) {
        double score = 0;
        for (int i = 0; i < a.length; i++) score += (double) a[i] * b[i];
        return Math.max(-1, Math.min(1, score));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) throw new IllegalStateException("Поиск по фото отключён в настройках сервера");
    }

    @PreDestroy
    public void close() {
        closing = true;
        stopRequested = true;
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) log.warn("Index worker did not stop within 15 seconds");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public record Entry(String relativePath, long size, long modified, String signature, String description, float[] vector) {}
    private record Match(Entry entry, double score) {}
    public record SearchHit(FileItemDto item, String description, double score) {}
    public record SearchPage(List<SearchHit> hits, int total, Status status) {}
    public record Status(boolean enabled, boolean running, int available, int scanned, int indexed, int unchanged,
                         int failed, String stage, String currentPath, String error, String visionModel, String embeddingModel, int processed) {}
}
