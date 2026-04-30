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
    private final Map<Path, Map<String, Object>> folderCache = new ConcurrentHashMap<>();
    public MetadataService(@Value("${app.ffprobe-path:ffprobe}") String ffprobePath) {
        this.ffprobePath = ffprobePath;
    }

    public Map<String, Object> readFileProperties(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            return readFolderProperties(path);
        }

        String name = path.getFileName().toString();
        String lower = name.toLowerCase();

        Map<String, Object> result = baseProperties(path);

        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
            result.putAll(readImageMetadata(path));
        } else if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".lrv") || lower.endsWith(".insv")) {
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
    /*private Map<String, Object> readFolderProperties(Path folder) throws IOException {

        Map<String, Object> cached = folderCache.get(folder);
        if (cached != null) {
            return cached;
        }

        Map<String, Object> map = baseProperties(folder);

        long[] files = {0};
        long[] folders = {0};
        long[] totalSize = {0};

        *//*try (Stream<Path> stream = Files.walk(folder)) {
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
                        } catch (IOException ignored) {}
                    });
        }*//*

        map.put("type", "folder");
        map.put("folderCount", folders[0]);
        map.put("fileCount", files[0]);
        map.put("size", totalSize[0]);

        folderCache.put(folder, map); // 🔥 сохраняем

        return map;
    }*/
    public Map<String, Object> readFolderStats(Path folder) throws IOException {
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
    }
    private final Map<Path, Long> createdCache = new ConcurrentHashMap<>();

    public long readCreatedAtMillisCached(Path path) {
        return createdCache.computeIfAbsent(
                path.toAbsolutePath().normalize(),
                this::readCreatedAtMillis
        );
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
        folderCache.clear();
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