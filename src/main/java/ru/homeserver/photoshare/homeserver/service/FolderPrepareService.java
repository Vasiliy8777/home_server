package ru.homeserver.photoshare.homeserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import ru.homeserver.photoshare.homeserver.dto.FileItemDto;
import ru.homeserver.photoshare.homeserver.dto.FolderPrepareJob;
import ru.homeserver.photoshare.homeserver.share.ShareService;
import ru.homeserver.photoshare.homeserver.util.HiddenPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.Semaphore;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.stream.Stream;

@Service
public class    FolderPrepareService {

    private final FileService fileService;
    private final MetadataService metadataService;
    private final Map<String, FolderPrepareJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, Long> jobAccess = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingRefreshes = new HashMap<>();
    private final Semaphore folderPreparationLimit = new Semaphore(2);
    private final ReentrantLock[] folderLocks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new ReentrantLock()).toArray(ReentrantLock[]::new);
    private final ThumbnailService thumbnailService;

    private final ScheduledThreadPoolExecutor folderRefreshPool = new ScheduledThreadPoolExecutor(1);
    private final ExecutorService preparationPool = Executors.newFixedThreadPool(2);
    private final ForkJoinPool metadataPool = new ForkJoinPool(4);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path folderCacheRoot;

    private final ShareService shareService;
    private final SqliteCacheStore database;

    public FolderPrepareService(
            FileService fileService,
            MetadataService metadataService,
            ThumbnailService thumbnailService,
            AppProperties appProperties, ShareService shareService
    ) {
        this(fileService, metadataService, thumbnailService, appProperties, shareService, null);
    }

    @Autowired
    public FolderPrepareService(FileService fileService, MetadataService metadataService,
                                ThumbnailService thumbnailService, AppProperties appProperties,
                                ShareService shareService, SqliteCacheStore database) {
        this.fileService = fileService;
        this.metadataService = metadataService;
        this.thumbnailService = thumbnailService;

        this.folderCacheRoot = Path.of(appProperties.getFolderCacheDir());
        this.shareService = shareService;
        this.database = database;
        folderRefreshPool.setRemoveOnCancelPolicy(true);
        try {
            Files.createDirectories(this.folderCacheRoot);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    public synchronized void refreshFolderCacheAsync(String relativePath) {
        String normalized = fileService.toRelative(fileService.resolveSafe(relativePath));
        ScheduledFuture<?> previous = pendingRefreshes.remove(normalized);
        if (previous != null) previous.cancel(false);
        pendingRefreshes.put(normalized, folderRefreshPool.schedule(() -> {
            synchronized (this) { pendingRefreshes.remove(normalized); }
            try {
                prepareFolderCacheOnly(normalized);
            } catch (Exception e) {
                System.out.println("Folder cache refresh failed: " + relativePath);
                e.printStackTrace();
            }
        }, 500, TimeUnit.MILLISECONDS));
    }
    public String start(
            String path,
            String sortField,
            String sortDirection,
            String groupMode,
            boolean periodEnabled,
            String periodFrom,
            String periodTo
    ) {
        String preparedPath = path;

        try {
            preparedPath = java.net.URLDecoder.decode(
                    preparedPath,
                    java.nio.charset.StandardCharsets.UTF_8
            );
        } catch (Exception ignored) {}

        final String finalPath = preparedPath;
        final String finalSortField = sortField;
        final String finalSortDirection = sortDirection;
        final String finalGroupMode = groupMode;
        final boolean finalPeriodEnabled = periodEnabled;
        final String finalPeriodFrom = periodFrom;
        final String finalPeriodTo = periodTo;

        System.out.println("START PREPARE PATH = " + finalPath);
        try {
            fileService.resolveSafe(finalPath);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid path");
        }
        String jobId = UUID.randomUUID().toString();

        FolderPrepareJob job = new FolderPrepareJob();
        job.id = jobId;
        job.path = finalPath;
        job.ready = false;
        job.progress = 0;
        job.sortField = finalSortField;
        job.sortDirection = finalSortDirection;

        pruneJobs();
        if (jobs.size() >= 96) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Очередь подготовки папок заполнена");
        jobs.put(jobId, job);
        jobAccess.put(jobId, System.currentTimeMillis());

        preparationPool.submit(() -> process(
                job,
                finalPath,
                finalSortField,
                finalSortDirection,
                finalGroupMode,
                finalPeriodEnabled,
                finalPeriodFrom,
                finalPeriodTo
        ));

        return jobId;
    }
    private void process(
            FolderPrepareJob job,
            String path,
            String sortField,
            String sortDirection,
            String groupMode,
            boolean periodEnabled,
            String periodFrom,
            String periodTo
    ) {
        boolean acquired = false;
        ReentrantLock folderLock = null;
        try {
            folderPreparationLimit.acquire();
            acquired = true;
            Path folder = fileService.resolveSafe(job.path);
            folderLock = lockFor(folder);
            folderLock.lockInterruptibly();

            if (!Files.exists(folder) || !Files.isDirectory(folder)) {
                job.stage = "Папка не найдена";
                job.items = List.of();
                job.total = 0;
                job.processed = 0;
                job.progress = 100;
                job.ready = true;
                return;
            }
            Set<String> sharedPaths = "shared".equals(groupMode)
                    ? shareService.activeSharedPaths()
                    : Set.of();
            if ("shared".equals(groupMode)) {
                List<FileItemDto> sharedItems = sharedPaths.stream()
                        .map(pathValue -> {
                            try {
                                return fileService.toItem(pathValue);
                            } catch (Exception e) {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .filter(item -> matchesGrouping(item, "all", periodEnabled, periodFrom, periodTo, Set.of()))
                        .sorted(createComparator(job.sortField, job.sortDirection))
                        .toList();

                job.stage = "Общие файлы и папки";
                job.items = sharedItems;
                job.total = sharedItems.size();
                job.processed = sharedItems.size();
                job.progress = 100;
                job.ready = true;
                return;
            }
            FolderSignature currentSignature = buildFolderSignature(folder);

            String folderKey = folder.toAbsolutePath().normalize().toString();
            boolean useDatabase = database != null && database.enabled();
            String signatureJson = objectMapper.writeValueAsString(currentSignature);
            if (useDatabase && signatureJson.equals(database.signature(folderKey))) {
                if ("all".equals(groupMode) && !periodEnabled) {
                    completeDatabaseJob(job, folderKey);
                    return;
                }
                List<FileItemDto> filtered = database.page(folderKey, job.sortField, job.sortDirection, 0, Integer.MAX_VALUE)
                        .stream().filter(item -> !isHiddenDirName(item.name()))
                        .filter(item -> matchesGrouping(item, groupMode, periodEnabled, periodFrom, periodTo, sharedPaths)).toList();
                job.items = filtered; job.total = filtered.size(); job.processed = filtered.size();
                job.progress = 100; job.ready = true; job.stage = "Каталог SQLite";
                return;
            }

            List<FileItemDto> cached = readFolderItemsCache(folder, currentSignature);

            if (cached != null) {
                if (useDatabase) {
                    database.saveFolder(folderKey, signatureJson, cached);
                    if ("all".equals(groupMode) && !periodEnabled) { completeDatabaseJob(job, folderKey); return; }
                }

                List<FileItemDto> filtered = cached.stream()
                        .filter(item -> !isHiddenDirName(item.name()))
                        .filter(item -> matchesGrouping(item, groupMode, periodEnabled, periodFrom, periodTo, sharedPaths))
                        .toList();

                filtered = new ArrayList<>(filtered);
                filtered.sort(createComparator(job.sortField, job.sortDirection));
                job.stage = "Чтение кеша папки";
                job.items = filtered;
                job.total = filtered.size();
                job.processed = filtered.size();
                job.progress = 100;
                job.ready = true;
                return;
            }
            job.stage = "Загрузка карточек";
            List<FileItemDto> all = fileService.list(job.path, 0, Integer.MAX_VALUE);
            job.total = all.size();
            job.processed = all.size();
            job.progress = 49;
            // Name/size order is stable before EXIF extraction. Date/group filters wait for enrichment.
            if (useDatabase && "all".equals(groupMode) && !periodEnabled && !"lastModified".equals(job.sortField)) {
                database.saveFolder(folderKey, "partial:" + signatureJson, all);
                completeDatabaseJob(job, folderKey);
                job.stage = "Галерея готова; обновление метаданных";
            }

            List<FileItemDto> source = all;
            AtomicInteger metadataDone = new AtomicInteger(0);
            int metadataTotal = source.size();

            job.stage = "Чтение метаданных, подготовка карточек";
            job.processed = 0;
            job.progress = 50;
            List<FileItemDto> enriched = metadataPool.submit(() ->
                    source.parallelStream().map(item -> {
                        try {
                            FileItemDto result = item;

                            if (!item.directory()) {
                                Path file = fileService.resolveSafe(item.relativePath());

                                long created = metadataService.readCreatedAtFromPropertiesCache(file);

                                result = new FileItemDto(
                                        item.name(),
                                        item.relativePath(),
                                        item.directory(),
                                        item.size(),
                                        item.type(),
                                        item.previewUrl(),
                                        item.thumbnailUrl(),
                                        item.downloadUrl(),
                                        item.lastModified(),
                                        created,
                                        item.fileCount(),
                                        item.folderCount(),
                                        item.hlsSupported(),
                                        item.hlsPrepareUrl(),
                                        item.hlsStatusUrl()
                                );
                            }

                            return result;

                        } catch (Exception ignored) {
                            return item;

                        } finally {
                            int done = metadataDone.incrementAndGet();

                            job.processed = done;
                            job.total = metadataTotal;
                            job.progress = metadataTotal > 0
                                    ? Math.min(99, 50 + Math.round(done * 49f / metadataTotal))
                                    : 99;
                        }
                    }).collect(java.util.stream.Collectors.toCollection(ArrayList::new))
            ).get();

            if (useDatabase) database.saveFolder(folderKey, signatureJson, enriched);
            else writeFolderItemsCache(folder, currentSignature, enriched);
            if (useDatabase && "all".equals(groupMode) && !periodEnabled) { completeDatabaseJob(job, folderKey); return; }

            List<FileItemDto> filtered = enriched.stream()
                    .filter(item -> !isHiddenDirName(item.name()))

                    .filter(item -> matchesGrouping(item, groupMode, periodEnabled, periodFrom, periodTo, sharedPaths))
                    .toList();

            filtered = new ArrayList<>(filtered);
            filtered.sort(createComparator(job.sortField, job.sortDirection));

            job.items = filtered;

            job.processed = filtered.size();
            job.ready = true;
            job.progress = 100;

        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            e.printStackTrace();
            job.stage = "Ошибка чтения папки";
            job.ready = true;
            job.items = List.of();
            job.progress = 100;
        } finally {
            if (folderLock != null && folderLock.isHeldByCurrentThread()) folderLock.unlock();
            job.complete = true;
            if (acquired) folderPreparationLimit.release();
            if (jobs.containsKey(job.id)) jobAccess.put(job.id, System.currentTimeMillis());
            pruneJobs();
        }
    }
    public FolderPrepareJob get(String jobId) {
        pruneJobs();
        if (jobs.containsKey(jobId)) jobAccess.put(jobId, System.currentTimeMillis());
        return jobs.get(jobId);
    }

    private void completeDatabaseJob(FolderPrepareJob job, String folderKey) throws IOException {
        synchronized (job) {
            String previous = job.databaseFolder;
            job.databaseFolder = database.acquireSnapshot(folderKey);
            database.releaseSnapshot(previous);
            job.items = List.of();
            job.total = database.count(job.databaseFolder);
            job.processed = job.total;
            job.stage = "Каталог SQLite";
            job.progress = 100;
            job.ready = true;
        }
    }

    public List<FileItemDto> items(String jobId, int offset, int limit) throws IOException {
        if (offset < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid page");
        FolderPrepareJob job = get(jobId);
        if (job == null || !job.ready) return List.of();
        synchronized (job) {
            if (job.databaseFolder != null) return database.page(job.databaseFolder, job.sortField, job.sortDirection, offset, limit);
        }
        if (offset >= job.items.size()) return List.of();
        return List.copyOf(job.items.subList(offset, (int) Math.min((long) offset + limit, job.items.size())));
    }

    private synchronized void pruneJobs() {
        long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(15);
        List<String> completed = jobs.entrySet().stream().filter(entry -> entry.getValue().complete)
                .map(Map.Entry::getKey).sorted(Comparator.comparingLong(id -> jobAccess.getOrDefault(id, 0L))).toList();
        int excess = Math.max(0, completed.size() - 32);
        for (String id : completed) {
            if (excess > 0 || jobAccess.getOrDefault(id, 0L) < cutoff) {
                FolderPrepareJob removed = jobs.remove(id);
                if (removed != null && database != null) {
                    synchronized (removed) { database.releaseSnapshot(removed.databaseFolder); }
                }
                jobAccess.remove(id);
                if (excess > 0) excess--;
            }
        }
    }

    @PreDestroy
    public void close() {
        preparationPool.shutdownNow();
        folderRefreshPool.shutdownNow();
        metadataPool.shutdownNow();
        if (database != null) jobs.values().forEach(job -> database.releaseSnapshot(job.databaseFolder));
    }
    private java.util.Comparator<FileItemDto> createComparator(String sortField, String sortDirection) {
        java.util.Comparator<FileItemDto> comparator;

        comparator = java.util.Comparator
                .comparing((FileItemDto item) -> !item.directory());

        if ("size".equals(sortField)) {
            comparator = comparator.thenComparingLong(item -> item.size());
        } else if ("lastModified".equals(sortField)) {
            comparator = comparator.thenComparingLong(item ->
                    item.createdAt() > 0 ? item.createdAt() : item.lastModified()
            );
        } else {
            comparator = comparator.thenComparing(
                    item -> item.name() == null ? "" : item.name().toLowerCase(java.util.Locale.ROOT)
            );
        }

        if ("desc".equals(sortDirection)) {
            comparator = comparator.reversed();
        }

        return comparator;
    }
    public Path folderCacheDir(Path folder) throws IOException {
        String hash = sha256(folder.toAbsolutePath().normalize().toString());
        Path dir = folderCacheRoot.resolve(hash);
        Files.createDirectories(dir);
        return dir;
    }
    private Path folderSignatureFile(Path folder) throws IOException {
        return folderCacheDir(folder).resolve("signature.json");
    }
    private Path folderManifestFile(Path folder) throws IOException {
        return folderCacheDir(folder).resolve("manifest.json");
    }
    private Path folderItemsDir(Path folder) throws IOException {
        Path dir = folderCacheDir(folder).resolve("items");
        Files.createDirectories(dir);
        return dir;
    }

    private FolderSignature buildFolderSignature(Path folder)
            throws IOException {

        if (!Files.exists(folder) || !Files.isDirectory(folder)) {
            return new FolderSignature(0, 0, 0, "");
        }

        long count = 0;
        long lastModifiedMax = 0;
        long totalSize = 0;

        List<String> names = new ArrayList<>();

        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(folder)) {

            for (Path child : stream) {

                try {

                    String name =
                            child.getFileName().toString();

                    if (isHiddenDirName(name)) {
                        continue;
                    }

                    count++;

                    long modified =
                            Files.getLastModifiedTime(child)
                                    .toMillis();

                    lastModifiedMax =
                            Math.max(lastModifiedMax, modified);

                    long size =
                            Files.isRegularFile(child)
                                    ? Files.size(child)
                                    : 0;

                    totalSize += size;

                    names.add(
                            name
                                    + "|"
                                    + modified
                                    + "|"
                                    + size
                                    + "|"
                                    + Files.isDirectory(child)
                    );

                } catch (Exception ignored) {
                }
            }
        }

        Collections.sort(names);

        String namesHash =
                sha256(String.join("\n", names));

        return new FolderSignature(
                count,
                lastModifiedMax,
                totalSize,
                namesHash
        );
    }
    private List<FileItemDto> readFolderItemsCache(Path folder, FolderSignature currentSignature) {
        try {
            Path signatureFile = folderSignatureFile(folder);
            Path manifestFile = folderManifestFile(folder);
            Path itemsDir = folderItemsDir(folder);

            if (!Files.exists(signatureFile) || !Files.exists(manifestFile) || !Files.isDirectory(itemsDir)) {
                return null;
            }

            FolderSignature cachedSignature =
                    objectMapper.readValue(signatureFile.toFile(), FolderSignature.class);

            if (!currentSignature.equals(cachedSignature)) {
                return null;
            }

            String[] files = objectMapper.readValue(manifestFile.toFile(), String[].class);
            if (files.length != currentSignature.count) {
                return null;
            }
            List<FileItemDto> items = new ArrayList<>();

            for (String fileName : files) {
                Path file = itemsDir.resolve(fileName);

                if (!Files.exists(file)) {
                    return null;
                }

                FileItemDto item = objectMapper.readValue(file.toFile(), FileItemDto.class);
                items.add(item);
            }
            return items;

        } catch (Exception e) {
            return null;
        }
    }
    private void writeFolderItemsCache(
            Path folder,
            FolderSignature signature,
            List<FileItemDto> items
    ) {
        try {
            Path itemsDir = folderItemsDir(folder);
            List<String> manifest = new ArrayList<>();

            for (FileItemDto item : items) {
                String fileName = sha256(item.relativePath()) + ".json";
                Path target = itemsDir.resolve(fileName);

                Files.createDirectories(target.getParent());

                objectMapper.writeValue(target.toFile(), item);

                manifest.add(fileName);
            }
            Path manifestFile = folderManifestFile(folder);

            Files.createDirectories(manifestFile.getParent());

            objectMapper.writeValue(manifestFile.toFile(), manifest);

            Path signatureFile = folderSignatureFile(folder);

            Files.createDirectories(signatureFile.getParent());

            objectMapper.writeValue(signatureFile.toFile(), signature);

        } catch (Exception e) {
            System.out.println("Folder items cache write failed: " + folder);
            e.printStackTrace();
        }
    }
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
    public void invalidateFolderCache(String relativePath) {
        ReentrantLock folderLock = null;
        try {
            Path folder = fileService.resolveSafe(relativePath);

            if (!Files.isDirectory(folder)) {
                folder = folder.getParent();
            }

            if (folder == null) return;
            metadataService.invalidate(folder);
            if (database != null && database.enabled()) {
                database.invalidateFolder(folder.toAbsolutePath().normalize().toString());
                return;
            }

            folderLock = lockFor(folder);
            folderLock.lock();

            Path dir = folderCacheDir(folder);

            if (Files.exists(dir)) {
                try (Stream<Path> stream = Files.walk(dir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (Exception ignored) {}
                            });
                }
            }
        } catch (Exception ignored) {}
        finally { if (folderLock != null && folderLock.isHeldByCurrentThread()) folderLock.unlock(); }
    }

    private ReentrantLock lockFor(Path folder) {
        return folderLocks[(folder.toAbsolutePath().normalize().hashCode() & Integer.MAX_VALUE) % folderLocks.length];
    }
    private static class FolderSignature {
        public long count;
        public long lastModifiedMax;
        public long totalSize;
        public String namesHash;

        public FolderSignature() {}

        public FolderSignature(long count, long lastModifiedMax, long totalSize, String namesHash) {
            this.count = count;
            this.lastModifiedMax = lastModifiedMax;
            this.totalSize = totalSize;
            this.namesHash = namesHash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FolderSignature that)) return false;
            return count == that.count
                    && lastModifiedMax == that.lastModifiedMax
                    && totalSize == that.totalSize
                    && Objects.equals(namesHash, that.namesHash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(count, lastModifiedMax, totalSize, namesHash);
        }
    }
    public void prepareFolderCacheOnly(String path) {
        FolderPrepareJob job = new FolderPrepareJob();
        job.id = UUID.randomUUID().toString();
        job.path = path;
        job.sortField = "name";
        job.sortDirection = "asc";
        job.ready = false;
        job.progress = 0;

        process(job, path, "name", "asc", "all", false, null, null);
    }

    private boolean isHiddenDirName(String name) {
        return HiddenPaths.isHiddenName(name);
    }

    private boolean matchesGrouping(
            FileItemDto item,
            String groupMode,
            boolean periodEnabled,
            String periodFrom,
            String periodTo,
            Set<String> sharedPaths
    ) {
        if (item == null) return false;

        if ("shared".equals(groupMode)) {
            if (!sharedPaths.contains(item.relativePath())) {
                return false;
            }
        }

        if ("photo".equals(groupMode)) {
            if (!"image".equals(item.type())) {
                return false;
            }
        }

        if ("video".equals(groupMode)) {
            if (!"video".equals(item.type())) {
                return false;
            }
        }

        if (!periodEnabled) {
            return true;
        }

        long date = item.createdAt() > 0
                ? item.createdAt()
                : item.lastModified();

        if (date <= 0) {
            return false;
        }

        LocalDate fileDate = java.time.Instant
                .ofEpochMilli(date)
                .atZone(ZoneId.systemDefault())
                .toLocalDate();

        if (periodFrom != null && !periodFrom.isBlank()) {
            LocalDate from = LocalDate.parse(periodFrom);

            if (fileDate.isBefore(from)) {
                return false;
            }
        }

        if (periodTo != null && !periodTo.isBlank()) {
            LocalDate to = LocalDate.parse(periodTo);

            if (fileDate.isAfter(to)) {
                return false;
            }
        }

        return true;
    }
    public boolean folderCacheValid(Path folder) {
        try {
            FolderSignature currentSignature = buildFolderSignature(folder);
            return readFolderItemsCache(folder, currentSignature) != null;
        } catch (Exception e) {
            return false;
        }
    }
}
