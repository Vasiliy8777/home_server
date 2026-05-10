package ru.homeserver.photoshare.homeserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import ru.homeserver.photoshare.homeserver.dto.FileItemDto;
import ru.homeserver.photoshare.homeserver.dto.FolderPrepareJob;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class    FolderPrepareService {

    private final FileService fileService;
    private final MetadataService metadataService;
    private final Map<String, FolderPrepareJob> jobs = new ConcurrentHashMap<>();
    private final ThumbnailService thumbnailService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path folderCacheRoot;

    public FolderPrepareService(
            FileService fileService,
            MetadataService metadataService,
            ThumbnailService thumbnailService,
            AppProperties appProperties
    ) {
        this.fileService = fileService;
        this.metadataService = metadataService;
        this.thumbnailService = thumbnailService;

        this.folderCacheRoot = Path.of(appProperties.getFolderCacheDir());
        try {
            Files.createDirectories(this.folderCacheRoot);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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

        String jobId = UUID.randomUUID().toString();

        FolderPrepareJob job = new FolderPrepareJob();
        job.id = jobId;
        job.path = finalPath;
        job.ready = false;
        job.progress = 0;
        job.sortField = finalSortField;
        job.sortDirection = finalSortDirection;

        jobs.put(jobId, job);

        new Thread(() -> process(
                job,
                finalPath,
                finalSortField,
                finalSortDirection,
                finalGroupMode,
                finalPeriodEnabled,
                finalPeriodFrom,
                finalPeriodTo
        )).start();

        return jobId;
    }
    /*public String start(String path, String sortField, String sortDirection) {*/
    /*public String start(
            String path,
            String sortField,
            String sortDirection,
            String groupMode,
            boolean periodEnabled,
            String periodFrom,
            String periodTo
    ) {
        try {
            path = java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        System.out.println("START PREPARE PATH = " + path);
        String jobId = UUID.randomUUID().toString();

        FolderPrepareJob job = new FolderPrepareJob();
        job.id = jobId;
        job.path = path;
        job.ready = false;
        job.progress = 0;
        job.sortField = sortField;
        job.sortDirection = sortDirection;

        jobs.put(jobId, job);

        *//*new Thread(() -> process(job)).start();*//*
        new Thread(() -> process(
                job,
                path,
                sortField,
                sortDirection,
                groupMode,
                periodEnabled,
                periodFrom,
                periodTo
        )).start();

        return jobId;
    }*/

    /*private void process(FolderPrepareJob job) {*/
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
        try {
            Path folder = fileService.resolveSafe(job.path);

            if (!Files.exists(folder) || !Files.isDirectory(folder)) {
                job.stage = "Папка не найдена";
                job.items = List.of();
                job.total = 0;
                job.processed = 0;
                job.progress = 100;
                job.ready = true;
                return;
            }

            FolderSignature currentSignature = buildFolderSignature(folder);

            List<FileItemDto> cached = readFolderItemsCache(folder, currentSignature);

            if (cached != null) {
                /*cached.sort(createComparator(job.sortField, job.sortDirection));*/
                List<FileItemDto> filtered = cached.stream()
                        .filter(item -> matchesGrouping(item, groupMode, periodEnabled, periodFrom, periodTo))
                        .toList();

                filtered = new ArrayList<>(filtered);
                filtered.sort(createComparator(job.sortField, job.sortDirection));
                job.stage = "Чтение кеша папки";
                /*job.items = cached;*/
                job.items = filtered;
                /*job.total = cached.size();
                job.processed = cached.size();*/
                job.total = filtered.size();
                job.processed = filtered.size();
                job.progress = 100;
                job.ready = true;
                return;
            }
            List<FileItemDto> all = new ArrayList<>();

            job.total = (int) fileService.countItems(job.path);
            job.processed = 0;
            job.progress = 0;

            int offset = 0;
            int limit = 200;

            while (true) {
                List<FileItemDto> batch = fileService.list(job.path, offset, limit);

                if (batch.isEmpty()) break;

                all.addAll(batch);
                job.stage = "Загрузка карточек";
                job.processed = all.size();
                job.progress = job.total > 0
                        ? Math.min(49, Math.round(job.processed * 50f / job.total))
                        : 50;


                offset += limit;

                Thread.sleep(10);
            }
            ForkJoinPool pool = new ForkJoinPool(4);

            List<FileItemDto> source = all;
            AtomicInteger metadataDone = new AtomicInteger(0);
            int metadataTotal = source.size();

            job.stage = "Чтение метаданных, подготовка карточек";
            job.processed = 0;
            job.progress = 50;
            List<FileItemDto> enriched = pool.submit(() ->
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
                                        item.folderCount()
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

            pool.shutdown();
            writeFolderItemsCache(folder, currentSignature, enriched);

            /*enriched.sort(createComparator(job.sortField, job.sortDirection));

            job.items = enriched;*/
            List<FileItemDto> filtered = enriched.stream()
                    .filter(item -> matchesGrouping(item, groupMode, periodEnabled, periodFrom, periodTo))
                    .toList();

            filtered = new ArrayList<>(filtered);
            filtered.sort(createComparator(job.sortField, job.sortDirection));

            job.items = filtered;
            /*job.processed = enriched.size();*/
            job.processed = filtered.size();
            job.ready = true;
            job.progress = 100;

        } catch (Exception e) {
            e.printStackTrace();

            job.ready = true;
            job.items = List.of();
            job.progress = 100;
        }
    }
    public FolderPrepareJob get(String jobId) {
        return jobs.get(jobId);
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

    private Path itemCacheFile(Path folder, FileItemDto item) throws IOException {
        String hash = sha256(item.relativePath());
        return folderItemsDir(folder).resolve(hash + ".json");
    }
    private FolderSignature buildFolderSignature(Path folder) throws IOException {
        if (!Files.exists(folder) || !Files.isDirectory(folder)) {
            return new FolderSignature(0, 0, 0, "");
        }

        long count = 0;
        long lastModifiedMax = 0;
        long totalSize = 0;
        List<String> names = new ArrayList<>();

        try (var stream = Files.list(folder)) {
            for (Path child : stream.toList()) {
                String name = child.getFileName().toString();

                if (name.equals(".metadata_cache")
                        || name.equals(".thumbnails")
                        || name.equals(".previews")
                        || name.equals(".preview_journal")
                        || name.equals(".folder_cache")
                        || name.equals(".upload_tmp")) {
                    continue;
                }

                count++;

                long modified = Files.getLastModifiedTime(child).toMillis();
                lastModifiedMax = Math.max(lastModifiedMax, modified);

                long size = Files.isRegularFile(child) ? Files.size(child) : 0;
                totalSize += size;

                names.add(name + "|" + modified + "|" + size + "|" + Files.isDirectory(child));
            }
        }

        Collections.sort(names);
        String namesHash = sha256(String.join("\n", names));

        return new FolderSignature(count, lastModifiedMax, totalSize, namesHash);
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
                Path temp = target.resolveSibling(fileName + ".tmp");

                objectMapper.writeValue(temp.toFile(), item);

                Files.move(
                        temp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                );

                manifest.add(fileName);
            }

            Path manifestFile = folderManifestFile(folder);
            Path manifestTemp = manifestFile.resolveSibling("manifest.json.tmp");

            objectMapper.writeValue(manifestTemp.toFile(), manifest);
            Files.move(manifestTemp, manifestFile, StandardCopyOption.REPLACE_EXISTING);

            Path signatureFile = folderSignatureFile(folder);
            Path signatureTemp = signatureFile.resolveSibling("signature.json.tmp");

            objectMapper.writeValue(signatureTemp.toFile(), signature);
            Files.move(signatureTemp, signatureFile, StandardCopyOption.REPLACE_EXISTING);

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

       /* process(job);*/
        process(job, path, "name", "asc", "all", false, null, null);
    }
    private boolean matchesGrouping(
            FileItemDto item,
            String groupMode,
            boolean periodEnabled,
            String periodFrom,
            String periodTo
    ) {
        if (item == null) return false;

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
}
