package ru.homeserver.photoshare.homeserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import ru.homeserver.photoshare.homeserver.dto.TotalCacheJobDto;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class TotalCacheService {

    private final FileService fileService;
    private final FolderPrepareService folderPrepareService;
    private final MetadataService metadataService;
    private final ThumbnailService thumbnailService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile boolean statusScanRunning = false;
    private volatile boolean statusScanStopRequested = false;

    private final Path journalFile;

    private final Object lock = new Object();
    private volatile boolean stopRequested = false;

    private final ExecutorService folderPool = Executors.newFixedThreadPool(1);
    private final ExecutorService metadataPool = Executors.newFixedThreadPool(3);
    private final ExecutorService thumbnailPool = Executors.newFixedThreadPool(1);

    private TotalCacheJobDto job = new TotalCacheJobDto();
    private volatile TotalCacheScan cachedScan = null;
    private volatile long cachedScanCreatedAt = 0;
    public TotalCacheService(
            FileService fileService,
            FolderPrepareService folderPrepareService,
            MetadataService metadataService,
            ThumbnailService thumbnailService,
            AppProperties appProperties
    ) throws IOException {
        this.fileService = fileService;
        this.folderPrepareService = folderPrepareService;
        this.metadataService = metadataService;
        this.thumbnailService = thumbnailService;

        Path dir = Path.of(appProperties.getMetadataCacheDir()).resolve("total-cache");
        Files.createDirectories(dir);

        this.journalFile = dir.resolve("total-cache-job.json");

        loadJournal();
    }
    private boolean shouldSkipPath(Path path) {
        String name = path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase();

        String full = path.toAbsolutePath().normalize().toString().toLowerCase();

        return name.equals(".metadata_cache")
                || name.equals(".thumbnails")
                || name.equals(".previews")
                || name.equals(".preview_journal")
                || name.equals(".folder_cache")
                || full.contains("\\.metadata_cache\\")
                || full.contains("\\.thumbnails\\")
                || full.contains("\\.previews\\")
                || full.contains("\\.preview_journal\\")
                || full.contains("\\.folder_cache\\")
                || full.contains("/.metadata_cache/")
                || full.contains("/.thumbnails/")
                || full.contains("/.previews/")
                || full.contains("/.preview_journal/")
                || full.contains("/.folder_cache/");
    }
    public TotalCacheJobDto getStatus() {
        synchronized (lock) {
            return job;
        }
    }

    public void start() {
        synchronized (lock) {
            if (job.running && !job.paused) return;

            stopRequested = false;
            job.running = true;
            job.paused = false;
            job.finished = false;
            job.stage = "Запуск тотального кеширования";
            saveJournal();
        }

        new Thread(this::runTotalCache, "total-cache-worker").start();
    }

    public void pause() {
        synchronized (lock) {
            job.paused = true;
            job.running = false;
            job.stage = "Пауза";
            saveJournal();
        }
    }

    public void resume() {
        start();
    }

    private void runTotalCache() {
        try {
            Path root = fileService.getRootPath();
            updateStage("Сканирование папок и файлов", "");

            /*TotalCacheScan scan = scanStorageRoot(root);*/
            TotalCacheScan scan = getOrBuildScan();

            List<Path> folders = scan.folders();
            List<Path> mediaFiles = scan.mediaFiles();
            /*List<Path> folders = new ArrayList<>();
            List<Path> files = new ArrayList<>();

            updateStage("Сканирование папок и файлов", "");
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && shouldSkipPath(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    folders.add(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!shouldSkipPath(file) && Files.isRegularFile(file)) {
                        files.add(file);
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    System.out.println("Skip unreadable path: " + file + " / " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
            List<Path> mediaFiles = files.stream()
                    .filter(file -> {
                        String type = detectType(file);
                        return "image".equals(type) || "video".equals(type);
                    })
                    .toList();*/
            synchronized (lock) {
                job.totalFolders = folders.size();

                job.totalFiles = mediaFiles.size();
                job.totalThumbnails = mediaFiles.size();

                job.processedFolders = 0;
                job.processedFiles = 0;
                job.processedThumbnails = 0;

                job.progress = 0;
                saveJournal();
            }
            List<Future<?>> tasks = new ArrayList<>();

            for (Path folder : folders) {
                tasks.add(folderPool.submit(() -> {
                    if (shouldStop()) return;

                    String rel = toRelative(folder);

                    updateStage("Кеш карточек папок", rel);

                    try {
                        folderPrepareService.prepareFolderCacheOnly(rel);
                    } catch (Exception ignored) {
                    }

                    synchronized (lock) {
                        job.processedFolders++;
                        updateProgressUnsafe();
                        saveJournal();
                    }
                }));
            }
            for (Path file : mediaFiles) {
            /*for (Path file : files) {*/
                tasks.add(metadataPool.submit(() -> {
                    if (shouldStop()) return;

                    String rel = toRelative(file);

                    /*updateStage("Кеш метаданных", rel);*/
                    synchronized (lock) {
                        if (job.processedFiles % 50 == 0) {
                            job.stage = "Кеш метаданных";
                            job.currentPath = rel;
                            updateProgressUnsafe();
                            saveJournal();
                        }
                    }
                    try {
                        metadataService.readFileProperties(file);
                    } catch (Exception ignored) {
                    }
                    synchronized (lock) {
                        job.processedFiles++;

                        if (job.processedFiles % 50 == 0
                                || job.processedFiles >= job.totalFiles) {
                            updateProgressUnsafe();
                            saveJournal();
                        }
                    }
                }));

                tasks.add(thumbnailPool.submit(() -> {
                    if (shouldStop()) return;

                    String rel = toRelative(file);

                    /*updateStage("Создание постеров", rel);*/
                    synchronized (lock) {
                        if (job.processedThumbnails % 50 == 0) {
                            job.stage = "Создание постеров";
                            job.currentPath = rel;
                            updateProgressUnsafe();
                            saveJournal();
                        }
                    }

                    try {
                        String type = detectType(file);

                        if ("image".equals(type)) {
                            if (!thumbnailService.hasImageThumbnail(file)) {
                                thumbnailService.getOrCreateImageThumbnail(file);
                            }
                        }

                        if ("video".equals(type)) {
                            if (!thumbnailService.hasVideoThumbnail(file)) {
                                thumbnailService.getOrCreateVideoThumbnail(file);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    /*synchronized (lock) {
                        job.processedThumbnails++;
                        updateProgressUnsafe();
                        saveJournal();
                    }*/
                    synchronized (lock) {
                        job.processedThumbnails++;

                        if (job.processedThumbnails % 50 == 0
                                || job.processedThumbnails >= job.totalThumbnails) {
                            updateProgressUnsafe();
                            saveJournal();
                        }
                    }
                }));
            }

            for (   Future<?> task : tasks) {
                if (shouldStop()) return;

                try {
                    task.get();
                } catch (Exception ignored) {
                }
            }
            synchronized (lock) {
                job.running = false;
                job.paused = false;
                job.finished = true;
                job.stage = "Готово";
                job.progress = 100;
                saveJournal();
            }

        } catch (Exception e) {
            synchronized (lock) {
                job.running = false;
                job.stage = "Ошибка: " + e.getMessage();
                saveJournal();
            }
        }
    }

    private boolean shouldStop() {
        synchronized (lock) {
            if (job.paused || stopRequested) {
                saveJournal();
                return true;
            }

            return false;
        }
    }
    public void cancel() {
        synchronized (lock) {
            stopRequested = true;

            job.running = false;
            job.paused = false;
            job.finished = false;
            job.stage = "Отменено";
            job.currentPath = "";

            saveJournal();
        }
    }public void startStatusScan() {
        synchronized (lock) {

            statusScanRunning = false;
            statusScanStopRequested = false;

            job.stage = "Подготовка анализа";
            job.progress = 0;

            saveJournal();
        }
    }
    public void abortAll() {
        stopRequested = true;
        statusScanStopRequested = true;

        synchronized (lock) {
            job.running = false;
            job.paused = false;
            job.finished = false;
            job.stage = "Аварийно остановлено";
            job.currentPath = "";

            statusScanRunning = false;

            saveJournal();
        }
    }
    public TotalCacheJobDto rebuildActualStatus() {

        synchronized (lock) {

            if (statusScanRunning) {
                return job;
            }

            if ("Анализ завершен".equals(job.stage)
                    || "Кеш готов".equals(job.stage)) {
                return job;
            }

            statusScanRunning = true;
            statusScanStopRequested = false;

            job.stage = "Анализ кеша";
            job.currentPath = "";
            job.progress = 0;

            saveJournal();
        }

        new Thread(() -> {

            try {

                Path root = fileService.getRootPath();
                /*TotalCacheScan scan = scanStorageRoot(root);*/
                TotalCacheScan scan = getOrBuildScan();

                List<Path> folders = scan.folders();
                List<Path> mediaFiles = scan.mediaFiles();

                int total = folders.size() + mediaFiles.size();
                /*List<Path> all = new ArrayList<>();

                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(root) && shouldSkipPath(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }

                        all.add(dir);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!shouldSkipPath(file) && Files.isRegularFile(file)) {
                            all.add(file);
                        }

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        System.out.println("Skip unreadable path during status scan: " + file + " / " + exc.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });

                int total = all.size();*/
                int processed = 0;

                long foldersCached = 0;
                long metadataCached = 0;
                long thumbsCached = 0;

                long totalFolders = 0;
                long totalFiles = 0;

                /*for (Path path : all) {

                    if (statusScanStopRequested) {
                        synchronized (lock) {
                            job.stage = "Анализ остановлен";
                            statusScanRunning = false;
                            saveJournal();
                        }
                        return;
                    }

                    processed++;

                    synchronized (lock) {

                        job.currentPath = toRelative(path);

                        job.progress = Math.min(
                                100,
                                Math.round(processed * 100f / total)
                        );

                        if (processed % 50 == 0) {
                            saveJournal();
                        }
                    }

                    try {

                        if (Files.isDirectory(path)) {

                            totalFolders++;

                            if (folderCacheExists(path)) {
                                foldersCached++;
                            }

                        } else if (Files.isRegularFile(path)) {

                            String type = detectType(path);

                            if ("image".equals(type) || "video".equals(type)) {

                                totalFiles++;

                                if (metadataService.propertiesCacheExists(path)) {
                                    metadataCached++;
                                }

                                boolean thumbExists =
                                        "image".equals(type)
                                                ? thumbnailService.hasImageThumbnail(path)
                                                : thumbnailService.hasVideoThumbnail(path);

                                if (thumbExists) {
                                    thumbsCached++;
                                }
                            }
                        }

                    } catch (Exception ignored) {
                    }
                }*/
                    for (Path path : folders) {
                        if (statusScanStopRequested) {
                            synchronized (lock) {
                                job.stage = "Анализ остановлен";
                                statusScanRunning = false;
                                saveJournal();
                            }
                            return;
                        }

                        processed++;

                        synchronized (lock) {
                            job.currentPath = toRelative(path);
                            job.progress = total > 0
                                    ? Math.min(100, Math.round(processed * 100f / total))
                                    : 100;

                            if (processed % 50 == 0) {
                                saveJournal();
                            }
                        }

                        totalFolders++;

                        if (folderCacheExists(path)) {
                            foldersCached++;
                        }
                    }

                for (Path path : mediaFiles) {
                    if (statusScanStopRequested) {
                        synchronized (lock) {
                            job.stage = "Анализ остановлен";
                            statusScanRunning = false;
                            saveJournal();
                        }
                        return;
                    }

                    processed++;

                    synchronized (lock) {
                        job.currentPath = toRelative(path);
                        job.progress = total > 0
                                ? Math.min(100, Math.round(processed * 100f / total))
                                : 100;

                        if (processed % 50 == 0) {
                            saveJournal();
                        }
                    }

                    totalFiles++;

                    if (metadataService.propertiesCacheExists(path)) {
                        metadataCached++;
                    }

                    try {
                        String type = detectType(path);

                        boolean thumbExists =
                                "image".equals(type)
                                        ? thumbnailService.hasImageThumbnail(path)
                                        : thumbnailService.hasVideoThumbnail(path);

                        if (thumbExists) {
                            thumbsCached++;
                        }
                    } catch (Exception ignored) {
                    }
                }
                synchronized (lock) {

                    job.totalFolders = totalFolders;
                    job.totalFiles = totalFiles;
                    job.totalThumbnails = totalFiles;

                    job.processedFolders = foldersCached;
                    job.processedFiles = metadataCached;
                    job.processedThumbnails = thumbsCached;

                    job.progress = 100;
                    job.stage = "Анализ завершен";

                    statusScanRunning = false;

                    saveJournal();
                }

            } catch (Exception e) {

                synchronized (lock) {
                    job.stage = "Ошибка анализа";
                    statusScanRunning = false;
                    saveJournal();
                }
            }

        }, "total-cache-status-scan").start();

        return job;
    }
    public void stopStatusScan() {

        statusScanStopRequested = true;

        synchronized (lock) {
            job.stage = "Остановка анализа...";
            saveJournal();
        }
    }
    private boolean folderCacheExists(Path folder) {
        try {
            Path dir = folderPrepareService.folderCacheDir(folder);

            return Files.exists(dir)
                    && Files.exists(dir.resolve("signature.json"))
                    && Files.exists(dir.resolve("manifest.json"));

        } catch (Exception e) {
            return false;
        }
    }
    public void resetStatus() {
        synchronized (lock) {
            stopRequested = false;

            job = new TotalCacheJobDto();
            job.running = false;
            job.paused = false;
            job.finished = false;
            job.stage = "Ожидание";
            job.currentPath = "";
            job.progress = 0;

            saveJournal();
        }
    }
    private void updateStage(String stage, String path) {
        synchronized (lock) {
            job.stage = stage;
            job.currentPath = path;
            updateProgressUnsafe();
            saveJournal();
        }
    }
    private TotalCacheScan scanStorageRoot(Path root) throws IOException {
        List<Path> folders = new ArrayList<>();
        List<Path> mediaFiles = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && shouldSkipPath(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                folders.add(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (shouldSkipPath(file)) {
                    return FileVisitResult.CONTINUE;
                }

                String type = detectType(file);

                if ("image".equals(type) || "video".equals(type)) {
                    mediaFiles.add(file);
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                System.out.println("Skip unreadable path: " + file + " / " + exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        return new TotalCacheScan(folders, mediaFiles);
    }
    private TotalCacheScan getOrBuildScan() throws IOException {

        TotalCacheScan scan = cachedScan;

        if (scan != null) {
            return scan;
        }

        synchronized (lock) {

            if (cachedScan == null) {

                Path root = fileService.getRootPath();

                TotalCacheScan diskScan = scanStorageRoot(root);

                List<Path> foldersFromTree = fileService.getCachedFolderPaths();

                cachedScan = new TotalCacheScan(
                        foldersFromTree,
                        diskScan.mediaFiles()
                );

                cachedScanCreatedAt = System.currentTimeMillis();
            }

            return cachedScan;
        }
    }
    public void invalidateStorageScanCache() {
        synchronized (lock) {
            cachedScan = null;
            cachedScanCreatedAt = 0;
        }
    }
    public void rebuildStorageScanCacheAsync() {
        invalidateStorageScanCache();

        new Thread(() -> {
            try {
                getOrBuildScan();
            } catch (Exception e) {
                System.out.println("Storage scan cache rebuild failed: " + e.getMessage());
            }
        }, "storage-scan-rebuild").start();
    }
    private record TotalCacheScan(
            List<Path> folders,
            List<Path> mediaFiles
    ) {}
    private void updateProgressUnsafe() {

        long total = job.totalFolders + job.totalFiles + job.totalThumbnails;
        long done = job.processedFolders + job.processedFiles + job.processedThumbnails;

        job.progress = total > 0
                ? Math.min(100, Math.round(done * 100f / total))
                : 0;
    }

    private String toRelative(Path path) {
        Path root = fileService.getRootPath();

        if (root.equals(path)) return "";

        return root.relativize(path)
                .toString()
                .replace("\\", "/");
    }

    private String detectType(Path file) {
        String name = file.getFileName().toString().toLowerCase();

        if (name.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|heic|heif|tif|tiff)$")) {
            return "image";
        }

        if (name.matches(".*\\.(mp4|mov|avi|mkv|webm|m4v|insv|lrv)$")) {
            return "video";
        }

        return "file";
    }

    private void loadJournal() {
        try {
            if (Files.exists(journalFile)) {
                job = objectMapper.readValue(journalFile.toFile(), TotalCacheJobDto.class);
                job.running = false;
            }
        } catch (Exception ignored) {
            job = new TotalCacheJobDto();
        }
    }

    private void saveJournal() {
        try {
            objectMapper.writeValue(journalFile.toFile(), job);
        } catch (Exception ignored) {
        }
    }
}
