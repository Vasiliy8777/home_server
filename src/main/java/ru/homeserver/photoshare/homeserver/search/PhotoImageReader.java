package ru.homeserver.photoshare.homeserver.search;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import ru.homeserver.photoshare.homeserver.util.PhotoFormats;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class PhotoImageReader {
    private static final Logger log = LoggerFactory.getLogger(PhotoImageReader.class);
    private final String magick;

    public PhotoImageReader(AppProperties properties) {
        magick = properties.getMagickPath() == null ? "magick" : properties.getMagickPath();
    }

    public byte[] readJpeg(Path photo) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Photo conversion cancelled");
        Path temp = Files.createTempFile("photo-search-", ".jpg");
        Path diagnostics = Files.createTempFile("photo-search-", ".stderr");
        long started = System.nanoTime();
        try {
            Process process;
            try {
                String input = photo.toAbsolutePath() + "[0]";
                if (PhotoFormats.isRaw(photo)) input = "dng:" + input;
                if (PhotoFormats.extension(photo).equals("hif")) input = "heic:" + input;
                process = new ProcessBuilder(magick, "-define", "dng:use-camera-wb=true", input, "-auto-orient",
                        "-thumbnail", "1280x1280>", "-background", "white", "-alpha", "remove",
                        "-colorspace", "sRGB", "-strip", "-quality", "85", temp.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(diagnostics.toFile()).start();
            } catch (IOException unavailable) {
                log.warn("CONVERTER_UNAVAILABLE path={} executable={} reason={}; trying ImageIO", photo, magick, unavailable.toString());
                return readWithImageIo(photo);
            }
            try {
                if (!process.waitFor(120, TimeUnit.SECONDS)) {
                    throw new IOException("Превышено время преобразования фотографии (120 секунд)");
                }
                if (process.exitValue() != 0 || Files.size(temp) == 0) {
                    String detail;
                    try (var input = Files.newInputStream(diagnostics)) {
                        detail = new String(input.readNBytes(8192), StandardCharsets.UTF_8).strip();
                    }
                    IOException failure = new IOException("ImageMagick exit=" + process.exitValue() + ": " + detail);
                    log.warn("CONVERSION_FAILED path={} extension={} reason={}; trying ImageIO", photo, PhotoFormats.extension(photo), failure.getMessage());
                    try {
                        return readWithImageIo(photo);
                    } catch (IOException fallback) {
                        failure.addSuppressed(fallback);
                        throw failure;
                    }
                }
                byte[] jpeg = Files.readAllBytes(temp);
                try (var input = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(jpeg))) {
                    var readers = ImageIO.getImageReaders(input);
                    if (!readers.hasNext()) throw new IOException("ImageMagick вернул нечитаемое изображение");
                    var reader = readers.next();
                    try {
                        reader.setInput(input);
                        log.info("CONVERTED path={} decoder=ImageMagick dimensions={}x{} bytes={} elapsedMs={}",
                                photo, reader.getWidth(0), reader.getHeight(0), jpeg.length,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                    } finally { reader.dispose(); }
                }
                return jpeg;
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
        } finally {
            cleanup(temp);
            cleanup(diagnostics);
        }
    }

    private static void cleanup(Path path) {
        try { Files.deleteIfExists(path); }
        catch (IOException e) { log.warn("TEMP_CLEANUP_FAILED path={} reason={}", path, e.toString()); }
    }

    private byte[] readWithImageIo(Path photo) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Photo conversion cancelled");
        BufferedImage source;
        try (var input = ImageIO.createImageInputStream(photo.toFile())) {
            if (input == null) throw new IOException("Не удалось прочитать фотографию");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Для формата " + PhotoFormats.extension(photo) + " требуется ImageMagick с соответствующим декодером");
            var reader = readers.next();
            try {
                reader.setInput(input);
                var parameters = reader.getDefaultReadParam();
                int sampling = Math.max(1, Math.max(reader.getWidth(0), reader.getHeight(0)) / 1280);
                parameters.setSourceSubsampling(sampling, sampling, 0, 0);
                source = reader.read(0, parameters);
                if (Thread.currentThread().isInterrupted()) {
                    source.flush();
                    throw new InterruptedException("Photo conversion cancelled");
                }
            } finally { reader.dispose(); }
        }
        double scale = Math.min(1, 1280.0 / Math.max(source.getWidth(), source.getHeight()));
        BufferedImage image = new BufferedImage(Math.max(1, (int) (source.getWidth() * scale)),
                Math.max(1, (int) (source.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, image.getWidth(), image.getHeight(), null);
        } finally {
            graphics.dispose();
            source.flush();
        }
        try (var bytes = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "jpeg", bytes)) throw new IOException("Недоступно кодирование JPEG");
            log.info("CONVERTED path={} decoder=ImageIO dimensions={}x{} bytes={}", photo,
                    image.getWidth(), image.getHeight(), bytes.size());
            return bytes.toByteArray();
        } finally {
            image.flush();
        }
    }
}
