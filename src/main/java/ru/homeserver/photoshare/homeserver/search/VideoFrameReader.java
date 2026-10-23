package ru.homeserver.photoshare.homeserver.search;

import ru.homeserver.photoshare.homeserver.config.AppProperties;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded, evenly spaced visual sampling; no audio is sent to the AI. */
final class VideoFrameReader extends PhotoImageReader {
    private static final Logger log = LoggerFactory.getLogger(VideoFrameReader.class);
    private final String ffmpeg;
    private final String ffprobe;

    VideoFrameReader(AppProperties app) {
        super(app);
        ffmpeg = app.getFfmpegPath() == null ? "ffmpeg" : app.getFfmpegPath();
        ffprobe = app.getFfprobePath() == null ? "ffprobe" : app.getFfprobePath();
    }

    @Override public byte[] readJpeg(Path video) throws IOException, InterruptedException {
        long started = System.nanoTime();
        Path dir = Files.createTempDirectory("video-search-");
        List<Path> temporary = new ArrayList<>();
        BufferedImage sheet = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        var graphics = sheet.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, 1280, 720);
            Path durationFile = dir.resolve("duration.txt");
            temporary.add(durationFile);
            run(List.of(ffprobe, "-v", "error", "-show_entries", "format=duration", "-of",
                    "default=noprint_wrappers=1:nokey=1", video.toAbsolutePath().toString()), durationFile);
            double duration;
            try { duration = Double.parseDouble(Files.readString(durationFile).strip()); }
            catch (NumberFormatException e) { throw new IOException("Не удалось определить длительность видео", e); }
            if (!Double.isFinite(duration) || duration <= 0) throw new IOException("Некорректная длительность видео");
            if (duration < 2.0) throw new MediaSkippedException("video-duration-below-2-seconds durationSeconds=" + duration);
            for (int i = 0; i < 6; i++) {
                Path frame = dir.resolve(i + ".jpg");
                Path output = dir.resolve("ffmpeg-" + i + ".txt");
                temporary.add(frame);
                temporary.add(output);
                run(List.of(ffmpeg, "-nostdin", "-v", "error", "-y", "-ss",
                        String.format(Locale.ROOT, "%.6f", duration * (i + 0.5) / 6), "-i", video.toAbsolutePath().toString(),
                        "-map", "0:v:0", "-frames:v", "1", "-vf",
                        "scale=426:360:force_original_aspect_ratio=decrease", frame.toString()), output);
                // Some short clips have no frame at the final seek position even
                // though FFmpeg reports success. Retry an earlier position once.
                if (!Files.isRegularFile(frame) || Files.size(frame) == 0) {
                    double earlier = Math.max(0, duration * i / 6 - 0.1);
                    log.warn("VIDEO_FRAME_RETRY path={} frame={} seekSeconds={}", video, i, earlier);
                    run(List.of(ffmpeg, "-nostdin", "-v", "error", "-y", "-ss",
                            String.format(Locale.ROOT, "%.6f", earlier), "-i", video.toAbsolutePath().toString(),
                            "-map", "0:v:0", "-frames:v", "1", "-vf",
                            "scale=426:360:force_original_aspect_ratio=decrease", frame.toString()), output);
                }
                if (!Files.isRegularFile(frame) || Files.size(frame) == 0)
                    throw new IOException("FFmpeg не извлёк кадр " + i + " из видео " + video.getFileName());
                BufferedImage image = ImageIO.read(frame.toFile());
                if (image == null) throw new IOException("Не удалось прочитать кадр видео");
                try { graphics.drawImage(image, (i % 3) * 426 + (426 - image.getWidth()) / 2,
                        (i / 3) * 360 + (360 - image.getHeight()) / 2, null); }
                finally { image.flush(); }
            }
            try (var bytes = new ByteArrayOutputStream()) {
                if (!ImageIO.write(sheet, "jpeg", bytes)) throw new IOException("Недоступен JPEG encoder");
                log.info("VIDEO_CONVERTED path={} frames=6 jpegBytes={} elapsedMs={}", video, bytes.size(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                return bytes.toByteArray();
            }
        } finally {
            graphics.dispose();
            sheet.flush();
            // Cleanup must not discard a successfully decoded video.
            for (Path path : temporary) {
                try { Files.deleteIfExists(path); }
                catch (IOException e) { log.warn("TEMP_CLEANUP_FAILED path={} reason={}", path, e.toString()); }
            }
            try { Files.deleteIfExists(dir); }
            catch (IOException e) { log.warn("TEMP_CLEANUP_FAILED path={} reason={}", dir, e.toString()); }
        }
    }

    private void run(List<String> command, Path output) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Video analysis stopped");
        Path diagnostics = Path.of(output + ".stderr");
        Process process = new ProcessBuilder(command).redirectOutput(output.toFile())
                .redirectError(diagnostics.toFile()).start();
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) throw new IOException("Превышено время извлечения кадров видео");
            if (process.exitValue() != 0) {
                String detail;
                try (var input = Files.newInputStream(diagnostics)) {
                    detail = new String(input.readNBytes(8192), java.nio.charset.StandardCharsets.UTF_8).strip();
                }
                throw new IOException("Ошибка извлечения кадров: " + command.getFirst()
                        + ", exit=" + process.exitValue() + ": " + detail);
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            try { Files.deleteIfExists(diagnostics); }
            catch (IOException e) { log.warn("TEMP_CLEANUP_FAILED path={} reason={}", diagnostics, e.toString()); }
        }
    }
}
