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
import org.springframework.stereotype.Service;
import ru.homeserver.photoshare.homeserver.config.AppProperties;

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


    private final Map<Path, Long> createdAtCache = new ConcurrentHashMap<>();
    private final Map<Path, Map<String, Object>> folderStatsCache = new ConcurrentHashMap<>();
    private final Map<Path, Map<String, Object>> filePropertiesCache = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path metadataCacheRoot;

    public MetadataService(AppProperties appProperties) throws IOException {
        this.ffprobePath = appProperties.getFfprobePath() != null
                ? appProperties.getFfprobePath()
                : "ffprobe";

        this.metadataCacheRoot = Path.of(appProperties.getMetadataCacheDir());
        Files.createDirectories(this.metadataCacheRoot);
    }
    private long computeFolderSignature(Path folder) {
        try (Stream<Path> stream = Files.list(folder)) {
            return stream
                    .mapToLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return 0L;
        }
    }
    private Path folderCacheDir(Path folder) throws IOException {
        String safeDir = folder.toAbsolutePath().normalize().toString()
                .replace(":", "")
                .replace("\\", "_")
                .replace("/", "_");

        Path dir = metadataCacheRoot.resolve(safeDir);
        Files.createDirectories(dir);

        return dir;
    }

    private String safeFileName(Path file) {
        return file.getFileName().toString()
                .replace("\\", "_")
                .replace("/", "_")
                .replace(":", "_");
    }

    private Path filePropertiesCacheFile(Path file) throws IOException {
        Path folder = file.getParent();

        if (folder == null) {
            folder = file.toAbsolutePath().normalize().getParent();
        }

        Path propertiesDir = folderCacheDir(folder).resolve("properties");
        Files.createDirectories(propertiesDir);

        return propertiesDir.resolve(safeFileName(file) + ".json");
    }

    private Map<String, Object> readSingleFilePropertiesCache(Path file) {
        try {
            Path cacheFile = filePropertiesCacheFile(file);

            if (!Files.exists(cacheFile)) {
                return null;
            }

            Map<String, Object> wrapper = objectMapper.readValue(cacheFile.toFile(), Map.class);

            long cachedModified = ((Number) wrapper.getOrDefault("_modifiedMillis", -1)).longValue();
            long currentModified = Files.getLastModifiedTime(file).toMillis();

            if (cachedModified != currentModified) {
                Files.deleteIfExists(cacheFile);
                return null;
            }

            Object value = wrapper.get("value");

            if (value instanceof Map<?, ?> map) {
                Map<String, Object> result = new HashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return result;
            }

            return null;

        } catch (Exception e) {
            return null;
        }
    }

    private void writeSingleFilePropertiesCache(Path file, Map<String, Object> value) {
        try {
            Path cacheFile = filePropertiesCacheFile(file);

            Map<String, Object> wrapper = new HashMap<>();
            wrapper.put("_path", file.toAbsolutePath().normalize().toString());
            wrapper.put("_modifiedMillis", Files.getLastModifiedTime(file).toMillis());
            wrapper.put("value", value);

            objectMapper.writeValue(cacheFile.toFile(), wrapper);

        } catch (Exception ignored) {
        }
    }
    public Map<String, Object> readFileProperties(Path path) throws IOException {
        Path key = path.toAbsolutePath().normalize();

        if (Files.isDirectory(key)) {
            return readFolderProperties(key);
        }

        Map<String, Object> memoryCached = filePropertiesCache.get(key);
        if (memoryCached != null) {
            return memoryCached;
        }

        Map<String, Object> diskCached = readSingleFilePropertiesCache(key);
        if (diskCached != null) {
            filePropertiesCache.put(key, diskCached);
            return diskCached;
        }

        Map<String, Object> result = readFilePropertiesUncached(key);

        filePropertiesCache.put(key, result);
        writeSingleFilePropertiesCache(key, result);

        return result;
    }
    public long readCreatedAtFromPropertiesCache(Path file) {
        try {
            Map<String, Object> props = readFileProperties(file);

            Object createdMillis = props.get("createdMillis");
            if (createdMillis instanceof Number number) {
                return number.longValue();
            }

            Object modifiedMillis = props.get("modifiedMillis");
            if (modifiedMillis instanceof Number number) {
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
        try {
            String lower = path.getFileName().toString().toLowerCase();

            if (lower.endsWith(".lrv") || lower.endsWith(".insv")) {
                return Files.getLastModifiedTime(path).toMillis();
            }

            Metadata metadata = ImageMetadataReader.readMetadata(path.toFile());

            ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);

            if (exif != null && exif.getDateOriginal() != null) {
                return exif.getDateOriginal().getTime();
            }

        } catch (Exception ignored) {}

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

        Map<String, Object> memoryCached = folderStatsCache.get(key);
        if (memoryCached != null) {
            return memoryCached;
        }

        Map<String, Object> result = folderStatsCache.computeIfAbsent(key, f -> {
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

                Map<String, Object> map = new HashMap<>();
                map.put("size", size);
                map.put("fileCount", files);
                map.put("folderCount", folders);
                return map;

            } catch (Exception e) {
                return Map.of("size", 0, "fileCount", 0, "folderCount", 0);
            }
        });

        return result;
    }
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