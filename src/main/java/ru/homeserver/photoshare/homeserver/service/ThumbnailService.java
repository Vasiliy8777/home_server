package ru.homeserver.photoshare.homeserver.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.Semaphore;

@Service
public class ThumbnailService {

    private final Semaphore thumbnailSemaphore = new Semaphore(2);

    private final String ffmpegPath;
    private final Path thumbnailsRoot;

    public ThumbnailService(
            @Value("${app.ffmpeg-path:ffmpeg}") String ffmpegPath,
            FileService fileService
    ) throws IOException {
        this.ffmpegPath = ffmpegPath;
        this.thumbnailsRoot = fileService.getRootPath().resolve(".thumbnails");
        Files.createDirectories(this.thumbnailsRoot);
    }

    public Path getOrCreateVideoThumbnail(Path videoPath) throws IOException {
        String ext = getExtension(videoPath.getFileName().toString()).toLowerCase(Locale.ROOT);

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
            thumbnailSemaphore.acquire();
            acquired = true;

            if (Files.exists(output) && Files.size(output) > 0) {
                return output;
            }

            generateVideoThumbnail(videoPath, output);

            if (Files.exists(output) && Files.size(output) > 0) {
                return output;
            }

            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (acquired) {
                thumbnailSemaphore.release();
            }
        }
    }

    public Path getOrCreateImageThumbnail(Path imagePath) throws IOException {
        String ext = getExtension(imagePath.getFileName().toString()).toLowerCase(Locale.ROOT);

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
            thumbnailSemaphore.acquire();
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
                thumbnailSemaphore.release();
            }
        }
    }

    private void generateVideoThumbnail(Path input, Path output) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-y",
                "-ss", "00:00:02",
                "-i", input.toAbsolutePath().toString(),
                "-vf", "thumbnail,scale=320:240",
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

            if (exitCode != 0) {
                Files.deleteIfExists(output);
                System.out.println("FFmpeg thumbnail generation failed for: " + input);
                System.out.println("FFmpeg exit code: " + exitCode);
                System.out.println("FFmpeg log:\n" + ffmpegLog);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(output);
            throw new IOException("Thumbnail generation interrupted", e);
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
            case "mp4", "mov", "avi", "mkv", "webm", "m4v", "ogv" -> true;
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

