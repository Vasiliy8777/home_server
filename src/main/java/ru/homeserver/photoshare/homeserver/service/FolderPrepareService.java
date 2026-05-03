package ru.homeserver.photoshare.homeserver.service;

import org.springframework.stereotype.Service;
import ru.homeserver.photoshare.homeserver.dto.FileItemDto;
import ru.homeserver.photoshare.homeserver.dto.FolderPrepareJob;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;

@Service
public class FolderPrepareService {

    private final FileService fileService;
    private final MetadataService metadataService;
    private final Map<String, FolderPrepareJob> jobs = new ConcurrentHashMap<>();

    public FolderPrepareService(FileService fileService, MetadataService metadataService) {
        this.fileService = fileService;
        this.metadataService = metadataService;
    }

    public String start(String path, String sortField, String sortDirection) {
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

        new Thread(() -> process(job)).start();

        return jobId;
    }

    /*private void process(FolderPrepareJob job) {
        try {
            List<FileItemDto> all = new ArrayList<>();

            int offset = 0;
            int limit = 200;

            while (true) {
                List<FileItemDto> batch = fileService.list(job.path, offset, limit);

                if (batch.isEmpty()) break;

                all.addAll(batch);

                offset += limit;

                job.progress = Math.min(95, offset / 50);

                // 🔥 даём дыхание CPU (важно для телефона)
                Thread.sleep(10);
            }

            job.items = all;
            job.ready = true;
            job.progress = 100;

        } catch (Exception e) {
            job.ready = true;
            job.items = List.of();
        }
    }*/
    private void process(FolderPrepareJob job) {
        try {
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

                job.processed = all.size();
                job.progress = job.total > 0
                        ? Math.min(99, Math.round(job.processed * 100f / job.total))
                        : 100;

                offset += limit;

                Thread.sleep(10);
            }
            ForkJoinPool pool = new ForkJoinPool(4);

            List<FileItemDto> source = all;

            List<FileItemDto> enriched = pool.submit(() ->
                    source.parallelStream().map(item -> {
                        if (!item.directory()) {
                            try {
                                long created = metadataService.readCreatedAtMillisCached(
                                        fileService.resolveSafe(item.relativePath())
                                );

                                return new FileItemDto(
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

                            } catch (Exception ignored) {}
                        }
                        return item;
                    }).collect(java.util.stream.Collectors.toCollection(ArrayList::new))
            ).get();

            pool.shutdown();

            enriched.sort(createComparator(job.sortField, job.sortDirection));

            job.items = enriched;
            job.processed = enriched.size();
            job.ready = true;
            job.progress = 100;

            /*all = enriched;
            all.sort(createComparator(job.sortField, job.sortDirection));
            job.items = all;
            job.processed = all.size();
            job.ready = true;
            job.progress = 100;*/

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
}
