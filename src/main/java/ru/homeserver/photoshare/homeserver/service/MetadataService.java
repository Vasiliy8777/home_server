package ru.homeserver.photoshare.homeserver.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.png.PngDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
public class MetadataService {

    private final String ffprobePath;
    /*private final Map<Path, Long> createdCache = new ConcurrentHashMap<>();
    private final Map<Path, Map<String, Object>> folderCache = new ConcurrentHashMap<>();*/

    private final Map<Path, Long> createdAtCache = new ConcurrentHashMap<>();
    private final Map<Path, Map<String, Object>> folderStatsCache = new ConcurrentHashMap<>();
    private final Map<Path, Map<String, Object>> filePropertiesCache = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path metadataCacheRoot;

    public MetadataService(
            @Value("${app.ffprobe-path:ffprobe}") String ffprobePath,
            @Value("${app.metadata-cache-dir:.metadata_cache}") String metadataCacheDir
    ) throws IOException {
        this.ffprobePath = ffprobePath;
        this.metadataCacheRoot = Path.of(metadataCacheDir);
        Files.createDirectories(this.metadataCacheRoot);
    }

    /*public MetadataService(@Value("${app.ffprobe-path:ffprobe}") String ffprobePath) {
        this.ffprobePath = ffprobePath;
    }*/
    private Path cacheFileFor(Path path, String suffix) {
        String key = Integer.toHexString(path.toAbsolutePath().normalize().toString().hashCode());
        return metadataCacheRoot.resolve(key + "." + suffix + ".json");
    }

    private Map<String, Object> readJsonCache(Path path, String suffix) {
        try {
            Path cacheFile = cacheFileFor(path, suffix);

            if (!Files.exists(cacheFile)) {
                return null;
            }

            Map<String, Object> cache = objectMapper.readValue(cacheFile.toFile(), Map.class);

            long cachedModified = ((Number) cache.getOrDefault("_modifiedMillis", -1)).longValue();
            long currentModified = Files.getLastModifiedTime(path).toMillis();

            if (cachedModified != currentModified) {
                Files.deleteIfExists(cacheFile);
                return null;
            }

            Object value = cache.get("value");

            if (value instanceof Map<?,?> map) {
                Map<String, Object> result = new HashMap<>();

                for (Map.Entry<?,?> entry : map.entrySet()) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }

                return result;
            }

            return null;

        } catch (Exception e) {
            return null;
        }
    }

    private void writeJsonCache(Path path, String suffix, Map

            value) {
        try {
            Path cacheFile = cacheFileFor(path, suffix);

            Map

                    wrapper = new HashMap<>();
            wrapper.put("_path", path.toAbsolutePath().normalize().toString());
            wrapper.put("_modifiedMillis", Files.getLastModifiedTime(path).toMillis());
            wrapper.put("value", value);

            objectMapper.writeValue(cacheFile.toFile(), wrapper);

        } catch (Exception ignored) {
        }
    }

    public Map<String, Object> readFileProperties(Path path) throws IOException {
        Path key = path.toAbsolutePath().normalize();

        Map<String, Object> diskCached = readJsonCache(key, "file-properties");
        if (diskCached != null) {
            filePropertiesCache.put(key, diskCached);
            return diskCached;
        }

        Map<String, Object> memoryCached = filePropertiesCache.get(key);
        if (memoryCached != null) {
            return memoryCached;
        }

        Map<String, Object> result = readFilePropertiesUncached(key);

        filePropertiesCache.put(key, result);
        writeJsonCache(key, "file-properties", result);

        return result;
    }

    /*public Map<String, Object> readFileProperties(Path path) throws IOException {*/
    private Map<String, Object> readFilePropertiesUncached(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            return readFolderProperties(path);
        }

        String name = path.getFileName().toString();
        String lower = name.toLowerCase();

        Map<String, Object> result = baseProperties(path);
        if (lower.endsWith(".lrv") || lower.endsWith(".insv")) {
            if (result.get("created") == null || result.get("created").toString().isBlank()) {
                result.put("created", result.get("modified"));
            }

            if (result.get("createdMillis") == null) {
                result.put("createdMillis", result.get("modifiedMillis"));
            }

            return result;
        }
        /*if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))*/
        if (
                lower.endsWith(".jpg") ||
                        lower.endsWith(".jpeg") ||
                        lower.endsWith(".png") ||
                        lower.endsWith(".heic") ||
                        lower.endsWith(".heif")
        ){
            result.putAll(readImageMetadata(path));
        } else if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv")
                || lower.endsWith(".webm")) {
            result.putAll(readVideoMetadata(path));
        }
        if (result.get("created") == null || result.get("created").toString().isBlank()) {
            result.put("created", result.get("modified"));
        }

        if (result.get("createdMillis") == null) {
            result.put("createdMillis", result.get("modifiedMillis"));
        }

        return result;
    }

    public long readCreatedAtMillis(Path path) {
        String lower = path.getFileName().toString().toLowerCase();

        if (lower.endsWith(".lrv") || lower.endsWith(".insv")) {
            try {
                return Files.getLastModifiedTime(path).toMillis();
            } catch (IOException e) {
                return 0L;
            }
        }
        try {
            Map<String, Object> props = readFileProperties(path);
            Object created = props.get("createdMillis");

            if (created instanceof Number number) {
                return number.longValue();
            }
        } catch (Exception ignored) {
        }

        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private Map<String, Object> baseProperties(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);

        Map<String, Object> map = new HashMap<>();
        map.put("name", path.getFileName().toString());
        map.put("type", Files.isDirectory(path) ? "folder" : Optional.ofNullable(Files.probeContentType(path)).orElse(""));
        map.put("size", Files.isDirectory(path) ? 0 : Files.size(path));

        map.put("created", "");
        map.put("createdMillis", null);

        map.put("modified", formatFileTime(attrs.lastModifiedTime().toMillis()));
        map.put("modifiedMillis", attrs.lastModifiedTime().toMillis());

        map.put("duration", "");
        map.put("resolution", "");
        map.put("device", "");
        map.put("location", "");

        return map;
    }
    public Map<String, Object> readFolderStats(Path folder) {
        Path key = folder.toAbsolutePath().normalize();

        Map<String, Object> diskCached = readJsonCache(key, "folder-stats");
        if (diskCached != null) {
            return diskCached;
        }

        Map<String, Object> result = folderStatsCache.computeIfAbsent(key, f -> {
            try {
                long size = 0;
                long files = 0;
                long folders = 0;

                try (Stream<Path> stream = Files.walk(f)) {
                    for (Path p : stream.toList()) {
                        if (Files.isDirectory(p)) {
                            if (!p.equals(f)) {
                                folders++;
                            }
                        } else {
                            files++;
                            size += Files.size(p);
                        }
                    }
                }

                Map<String, Object> map = new HashMap<>();
                map.put("size", size);
                map.put("fileCount", files);
                map.put("folderCount", folders);

                return map;

            } catch (Exception e) {
                return Map.of(
                        "size", 0,
                        "fileCount", 0,
                        "folderCount", 0
                );
            }
        });

        writeJsonCache(key, "folder-stats", result);

        return result;
    }
    public long readCreatedAtMillisCached(Path file) {
        try {
            Map<String, Object> props = readFileProperties(file);

            Object created = props.get("createdMillis");
            if (created instanceof Number number) {
                return number.longValue();
            }

            Object modified = props.get("modifiedMillis");
            if (modified instanceof Number number) {
                return number.longValue();
            }

        } catch (Exception ignored) {
        }

        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
    /*public Map<String, Object> readFolderStats(Path folder) {
        *//*return folderStatsCache.computeIfAbsent(folder, f -> {*//*

        Path key = folder.toAbsolutePath().normalize();
        return folderStatsCache.computeIfAbsent(key, f -> {
            try {
                long size = 0;
                long files = 0;
                long folders = 0;

                try (Stream<Path> stream = Files.walk(f)) {
                    for (Path p : stream.toList()) {
                        if (Files.isDirectory(p)) {
                            if (!p.equals(f)) folders++;
                        } else {
                            files++;
                            size += Files.size(p);
                        }
                    }
                }

                Map<String, Object> result = new HashMap<>();
                result.put("size", size);
                result.put("fileCount", files);
                result.put("folderCount", folders);

                return result;

            } catch (Exception e) {
                return Map.of(
                        "size", 0,
                        "fileCount", 0,
                        "folderCount", 0
                );
            }
        });
    }*/
    /*public Map<String, Object> readFolderStats(Path folder) throws IOException {
        Path key = folder.toAbsolutePath().normalize();

        Map<String, Object> cached = folderCache.get(key);
        if (cached != null) {
            return cached;
        }

        long[] files = {0};
        long[] folders = {0};
        long[] totalSize = {0};

        try (Stream<Path> stream = Files.walk(folder)) {
            stream
                    .filter(p -> !p.equals(folder))
                    .forEach(p -> {
                        try {
                            if (Files.isDirectory(p)) {
                                folders[0]++;
                            } else {
                                files[0]++;
                                totalSize[0] += Files.size(p);
                            }
                        } catch (IOException ignored) {
                        }
                    });
        }

        Map<String, Object> result = new HashMap<>();
        result.put("fileCount", files[0]);
        result.put("folderCount", folders[0]);
        result.put("size", totalSize[0]);
        result.put("folderStatsLoading", false);

        folderCache.put(key, result);

        return result;
    }*/


    /*public long readCreatedAtMillisCached(Path path) {
        return createdCache.computeIfAbsent(
                path.toAbsolutePath().normalize(),
                this::readCreatedAtMillis
        );
    }*/
    /*public long readCreatedAtMillisCached(Path file) {
        Path key = file.toAbsolutePath().normalize();

        Map<String, Object> diskCached = readJsonCache(key, "created-at");
        if (diskCached != null && diskCached.get("createdAt") instanceof Number number) {
            return number.longValue();
        }

        long createdAt = createdAtCache.computeIfAbsent(key, f -> {
            try {
                return readCreatedAtMillis(f);
            } catch (Exception e) {
                try {
                    return Files.getLastModifiedTime(f).toMillis();
                } catch (IOException ex) {
                    return 0L;
                }
            }
        });

        writeJsonCache(key, "created-at", Map.of("createdAt", createdAt));

        return createdAt;
    }*/
    /*public long readCreatedAtMillisCached(Path file) {
        *//*return createdAtCache.computeIfAbsent(file, f -> {*//*

        Path key = file.toAbsolutePath().normalize();
        return createdAtCache.computeIfAbsent(key, f -> {
            try {
                return Files.readAttributes(f, BasicFileAttributes.class)
                        .creationTime()
                        .toMillis();
            } catch (IOException e) {
                return System.currentTimeMillis();
            }
        });
    }*/
    private Map<String, Object> readFolderProperties(Path folder) throws IOException {
        Map<String, Object> map = baseProperties(folder);

        map.put("type", "folder");
        map.put("folderCount", "");
        map.put("fileCount", "");
        map.put("size", 0);
        map.put("folderStatsLoading", true);

        return map;
    }
    private Map<String, Object> readImageMetadata(Path file) {
        Map<String, Object> map = new HashMap<>();

        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());

            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            ExifSubIFDDirectory sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);

            if (ifd0 != null) {
                String make = ifd0.getString(ExifIFD0Directory.TAG_MAKE);
                String model = ifd0.getString(ExifIFD0Directory.TAG_MODEL);
                map.put("device", ((make != null ? make : "") + " " + (model != null ? model : "")).trim());
            }

            if (sub != null) {
                if (sub.getDateOriginal() != null) {
                    long millis = sub.getDateOriginal().getTime();
                    map.put("created", formatFileTime(millis));
                    map.put("createdMillis", millis);
                } else if (sub.getDateDigitized() != null) {
                    long millis = sub.getDateDigitized().getTime();
                    map.put("created", formatFileTime(millis));
                    map.put("createdMillis", millis);
                }
            }

            if (gps != null && gps.getGeoLocation() != null) {
                map.put("location", gps.getGeoLocation().getLatitude() + ", " + gps.getGeoLocation().getLongitude());
            }

            JpegDirectory jpeg = metadata.getFirstDirectoryOfType(JpegDirectory.class);
            if (jpeg != null) {
                map.put("resolution", jpeg.getImageWidth() + "×" + jpeg.getImageHeight());
            }

            PngDirectory png = metadata.getFirstDirectoryOfType(PngDirectory.class);
            if (png != null) {
                Integer w = png.getInteger(PngDirectory.TAG_IMAGE_WIDTH);
                Integer h = png.getInteger(PngDirectory.TAG_IMAGE_HEIGHT);
                if (w != null && h != null) {
                    map.put("resolution", w + "×" + h);
                }
            }

        } catch (Exception e) {
            map.put("metadataError", e.getMessage());
        }

        return map;
    }

    private Map<String, Object> readVideoMetadata(Path file) {
        Map<String, Object> map = new HashMap<>();
        String lower = file.getFileName().toString().toLowerCase();

        if (lower.endsWith(".lrv") || lower.endsWith(".insv")) {
            return map;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffprobePath,
                    "-v", "quiet",
                    "-print_format", "json",
                    "-show_format",
                    "-show_streams",
                    file.toAbsolutePath().toString()
            );

            Process process = pb.start();

            String json;
            try (InputStream is = process.getInputStream()) {
                json = new String(is.readAllBytes());
            }

            int exitCode = process.waitFor();
            if (exitCode != 0 || json.isBlank()) {
                return map;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);

            JsonNode format = root.get("format");

            if (format != null && format.has("duration")) {
                double seconds = format.get("duration").asDouble();
                map.put("duration", formatDuration(seconds));
            }

            if (format != null && format.has("tags")) {
                JsonNode tags = format.get("tags");

                if (tags.has("creation_time")) {
                    String raw = tags.get("creation_time").asText();

                    try {
                        Instant instant = Instant.parse(raw);
                        map.put("created", formatFileTime(instant.toEpochMilli()));
                        map.put("createdMillis", instant.toEpochMilli());
                    } catch (Exception ignored) {
                    }
                }
            }

            JsonNode streams = root.get("streams");
            if (streams != null && streams.isArray()) {
                for (JsonNode stream : streams) {
                    if ("video".equals(stream.path("codec_type").asText())) {
                        int width = stream.path("width").asInt();
                        int height = stream.path("height").asInt();

                        if (width > 0 && height > 0) {
                            map.put("resolution", width + "×" + height);
                        }

                        break;
                    }
                }
            }

        } catch (Exception e) {
            map.put("metadataError", e.getMessage());
        }

        return map;
    }

    private String formatFileTime(long millis) {
        return Instant.ofEpochMilli(millis)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }
   /* public void clearFolderCache() {
        *//*folderCache.clear();*//*
        folderStatsCache.clear();
        createdAtCache.clear();
        try (Stream<Path> stream = Files.list(metadataCacheRoot)) {
            stream
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }*/

    public void clearFolderCache() {
        folderStatsCache.clear();
        createdAtCache.clear();
        filePropertiesCache.clear();

        try (Stream<Path> stream = Files.list(metadataCacheRoot)) {
            stream
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private String formatDuration(double seconds) {
        long total = Math.round(seconds);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;

        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }

        return String.format("%d:%02d", m, s);
    }
}