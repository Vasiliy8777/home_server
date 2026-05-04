package ru.homeserver.photoshare.homeserver.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.homeserver.photoshare.homeserver.config.AppProperties;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
public class ThumbnailService {

    private final Semaphore imageSemaphore = new Semaphore(2);
    private final Semaphore videoSemaphore = new Semaphore(1); // 👈 ограничить ffmpeg

    private final String ffmpegPath;

    private final String magickPath;
    private final Path thumbnailsRoot;
    public ThumbnailService(AppProperties appProperties) throws IOException {
        this.ffmpegPath = appProperties.getFfmpegPath() != null
                ? appProperties.getFfmpegPath()
                : "ffmpeg";

        this.magickPath = appProperties.getMagickPath() != null
                ? appProperties.getMagickPath()
                : "magick";

        this.thumbnailsRoot = Path.of(appProperties.getThumbnailCacheDir());
        Files.createDirectories(this.thumbnailsRoot);
    }
    private String formatFileTime(long millis) {
        return java.time.Instant.ofEpochMilli(millis)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
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
    public Path getOrCreateVideoThumbnail(Path videoPath) throws IOException, InterruptedException {
        String ext = getExtension(videoPath.getFileName().toString()).toLowerCase(Locale.ROOT);

        if ("insv".equals(ext) || "lrv".equals(ext)) {

            String hash = sha256(videoPath.toAbsolutePath().normalize().toString());
            Path thumbnail = thumbnailsRoot.resolve(hash + ".jpg");

            if (Files.exists(thumbnail) && Files.size(thumbnail) > 0) {
                return thumbnail;
            }

            boolean acquired = false;

            try {
                videoSemaphore.acquire();
                acquired = true;

                if (Files.exists(thumbnail) && Files.size(thumbnail) > 0) {
                    return thumbnail;
                }

                ProcessBuilder pb = new ProcessBuilder(
                        ffmpegPath,
                        "-y",
                        "-i", videoPath.toAbsolutePath().toString(),
                        "-vf", "select=eq(n\\,0),scale=320:-2",
                        "-frames:v", "1",
                        "-q:v", "5",
                        thumbnail.toAbsolutePath().toString()
                );

                pb.redirectErrorStream(true);

                Process process = pb.start();

                StringBuilder log = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.append(line).append(System.lineSeparator());
                    }
                }

                int exitCode = process.waitFor();

                if (exitCode != 0 || !Files.exists(thumbnail) || Files.size(thumbnail) == 0) {
                    Files.deleteIfExists(thumbnail);
                    System.out.println("FFmpeg INSV/LRV thumbnail skipped for: " + videoPath);
                    return null;
                }

                return thumbnail;

            } finally {
                if (acquired) {
                    videoSemaphore.release();
                }
            }
        }

        if (!isVideoExtension(ext)) {
            return null;
        }

        String hash = sha256(videoPath.toAbsolutePath().normalize().toString());
        Path output = thumbnailsRoot.resolve(hash + ".jpg");

        if (Files.exists(output) && Files.size(output) > 0) {
            return output;
        }

        boolean acquired = false;
        try {
            /*thumbnailSemaphore.acquire();*/
            videoSemaphore.acquire();
            acquired = true;

            if (Files.exists(output) && Files.size(output) > 0) {
                return output;
            }
            try {
                generateVideoThumbnail(videoPath, output);
            } catch (Exception e) {
                Files.deleteIfExists(output);
                System.out.println("⚠️ Video thumbnail skipped for: " + videoPath);
                System.out.println("Reason: too short / no frames / corrupted video");
                return null;
            }

            if (Files.exists(output) && Files.size(output) > 0) {
                return output;
            }

            Files.deleteIfExists(output);
            System.out.println("⚠️ Video thumbnail skipped for: " + videoPath);
            System.out.println("Reason: no thumbnail file created");

            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (acquired) {
                videoSemaphore.release();
            }
        }
    }
    public Path getOrCreateHeicThumbnail(Path file) throws IOException {
        String hash = sha256(file.toAbsolutePath().normalize().toString());
        Path thumbnail = thumbnailsRoot.resolve(hash + ".heic.jpg");

        if (Files.exists(thumbnail) && Files.size(thumbnail) > 0) {
            return thumbnail;
        }

        ProcessBuilder pb = new ProcessBuilder(
                magickPath,
                file.toAbsolutePath().toString(),
                "-auto-orient",
                "-thumbnail",
                "1200x1200",
                "-quality",
                "85",
                thumbnail.toAbsolutePath().toString()
        );

        pb.redirectErrorStream(true);
        try {
        Process process = pb.start();

        StringBuilder log = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                log.append(line).append(System.lineSeparator());
            }
        }


            int exitCode = process.waitFor();
            if (exitCode != 0 || !Files.exists(thumbnail) || Files.size(thumbnail) == 0) {
                Files.deleteIfExists(thumbnail);

                System.out.println("⚠️ HEIC thumbnail skipped for: " + file);
                System.out.println("Reason: ImageMagick failed (too short/corrupted/unsupported)");
                System.out.println("Exit code: " + exitCode);

                return null;
            }
            return thumbnail;

        } catch (Exception e) {
            Files.deleteIfExists(thumbnail);

            System.out.println("⚠️ HEIC thumbnail skipped for: " + file);
            System.out.println("Reason: exception during conversion");
            e.printStackTrace();

            return null;
        }
    }
    public Path getOrCreateImageThumbnail(Path imagePath) throws IOException {
        String ext = getExtension(imagePath.getFileName().toString()).toLowerCase(Locale.ROOT);
        String name = imagePath.getFileName().toString().toLowerCase();

        if (name.endsWith(".heic") || name.endsWith(".heif")) {
            return getOrCreateHeicThumbnail(imagePath);
        }
        if (!isImageExtension(ext)) {
            return null;
        }

        String hash = sha256(imagePath.toAbsolutePath().normalize().toString());
        Path output = thumbnailsRoot.resolve(hash + ".jpg");

        if (Files.exists(output) && Files.size(output) > 0) {
            return output;
        }

        boolean acquired = false;
        try {
           /* thumbnailSemaphore.acquire();*/
            imageSemaphore.acquire();
            acquired = true;

            if (Files.exists(output) && Files.size(output) > 0) {
                return output;
            }

            generateImageThumbnail(imagePath, output, 400, 300, 0.82f);

            if (Files.exists(output) && Files.size(output) > 0) {
                return output;
            }

            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (acquired) {
                imageSemaphore.release();
            }
        }
    }
    private void generateVideoThumbnail(Path input, Path output) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-y",

                "-i", input.toAbsolutePath().toString(),

                "-vf", "select=eq(n\\,0),scale=320:-2",
                "-frames:v", "1",

                output.toAbsolutePath().toString()
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder ffmpegLog = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                ffmpegLog.append(line).append(System.lineSeparator());
            }
        }

        try {
            int exitCode = process.waitFor();

            if (exitCode != 0 || !Files.exists(output) || Files.size(output) == 0) {
                Files.deleteIfExists(output);

                System.out.println("FFmpeg thumbnail skipped for: " + input);
                System.out.println("Reason: no video frame or too short/corrupted video");
                System.out.println("FFmpeg exit code: " + exitCode);
                return;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(output);
            return;
        }
    }

    private void generateImageThumbnail(Path input, Path output, int maxWidth, int maxHeight, float quality) throws IOException {
        BufferedImage original = ImageIO.read(input.toFile());

        if (original == null) {
            throw new IOException("Unsupported image format: " + input);
        }

        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();

        double scale = Math.min(
                (double) maxWidth / originalWidth,
                (double) maxHeight / originalHeight
        );

        scale = Math.min(scale, 1.0);

        int targetWidth = Math.max(1, (int) Math.round(originalWidth * scale));
        int targetHeight = Math.max(1, (int) Math.round(originalHeight * scale));

        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = resized.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(original, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }

        writeJpeg(resized, output, quality);
    }

    private void writeJpeg(BufferedImage image, Path output, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available");
        }

        ImageWriter writer = writers.next();

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(output.toFile())) {
            writer.setOutput(ios);

            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }

            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private boolean isVideoExtension(String ext) {
        return switch (ext.toLowerCase()) {
            case "mp4", "mov", "avi", "mkv", "webm", "m4v", "ogv", "insv", "lrv" -> true;
            default -> false;
        };
    }

    private boolean isImageExtension(String ext) {
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "tif", "tiff",
                 "avif", "heic", "heif", "raw", "cr2", "cr3", "nef", "arw", "dng" -> true;
            default -> false;
        };
    }

    private String getExtension(String filename) {
        int index = filename.lastIndexOf('.');
        return index >= 0 ? filename.substring(index + 1) : "";
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

