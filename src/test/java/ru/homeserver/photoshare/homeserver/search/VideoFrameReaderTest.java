package ru.homeserver.photoshare.homeserver.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class VideoFrameReaderTest {
    @TempDir Path temp;

    @Test
    @EnabledIfEnvironmentVariable(named = "VIDEO_SEARCH_LIVE_SAMPLE", matches = ".+")
    void analyzesRealSampleWithoutChangingOriginal() throws Exception {
        Path video = Path.of(System.getenv("VIDEO_SEARCH_LIVE_SAMPLE"));
        var before = java.nio.file.Files.readAttributes(video, java.nio.file.attribute.BasicFileAttributes.class);
        long started = System.nanoTime();
        byte[] frames = new VideoFrameReader(new AppProperties()).readJpeg(video);
        long converted = System.nanoTime();
        String caption = new OllamaPhotoAiClient(new PhotoSearchProperties()).describeVideo(frames);
        assertFalse(caption.isBlank());
        var after = java.nio.file.Files.readAttributes(video, java.nio.file.attribute.BasicFileAttributes.class);
        assertEquals(before.size(), after.size());
        assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
        System.out.printf("LIVE VIDEO: convertMs=%d captionMs=%d captionChars=%d%n",
                TimeUnit.NANOSECONDS.toMillis(converted - started),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - converted), caption.length());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles = {2.0, 1.96, 0.04})
    @EnabledIfEnvironmentVariable(named = "RUN_VIDEO_FRAME_TEST", matches = "1")
    void extractsSixFramesFromRealVideo(double duration) throws Exception {
        Path video = temp.resolve("sample.mp4");
        var process = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-y", "-f", "lavfi",
                "-i", "color=c=red:s=320x240:d=" + duration, "-c:v", "libx264", video.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        } finally { if (process.isAlive()) process.destroyForcibly(); }
        var reader = new VideoFrameReader(new AppProperties());
        if (duration < 2.0) {
            assertThrows(MediaSkippedException.class, () -> reader.readJpeg(video));
            return;
        }
        var sheet = ImageIO.read(new ByteArrayInputStream(reader.readJpeg(video)));
        try {
            assertEquals(1280, sheet.getWidth());
            assertEquals(720, sheet.getHeight());
            for (int i = 0; i < 6; i++) {
                var color = new java.awt.Color(sheet.getRGB((i % 3) * 426 + 213, (i / 3) * 360 + 180));
                assertTrue(color.getRed() > 180 && color.getGreen() < 50 && color.getBlue() < 50);
            }
        } finally { sheet.flush(); }
    }
}
