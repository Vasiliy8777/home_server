package ru.homeserver.photoshare.homeserver.music;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class MusicSourceServiceTest {
    @TempDir Path root;
    MusicSourceService sources;
    @BeforeEach void setup() {
        var properties = new MusicProperties(); properties.setRoot(root.toString());
        sources = new MusicSourceService(properties, new ObjectMapper());
    }
    private boolean available(String command) {
        try {
            var process = new ProcessBuilder(command, "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try { return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0; }
            finally { if (process.isAlive()) process.destroyForcibly(); }
        } catch (Exception e) { return false; }
    }
    static byte[] wave(int seconds) {
        int size = seconds * 8000 * 2;
        var data = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN);
        data.put("RIFF".getBytes()).putInt(36 + size).put("WAVEfmt ".getBytes()).putInt(16)
                .putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16)
                .put("data".getBytes()).putInt(size);
        return data.array();
    }
    @Test void verifiesRealAudioPreservesOriginalAndCreatesReusableNormalizedSource() throws Exception {
        Assumptions.assumeTrue(available("ffmpeg") && available("ffprobe"));
        byte[] audio = wave(10);
        var source = sources.upload("../../образец.wav", audio.length, new ByteArrayInputStream(audio));
        assertEquals("образец.wav", source.name());
        assertEquals(10, source.duration(), .01);
        assertEquals(source, sources.get(source.id()));
        assertTrue(sources.audio(source.id()).startsWith(root.resolve("sources")));
        assertTrue(Files.size(sources.audio(source.id())) > audio.length);
        assertArrayEquals(audio, Files.readAllBytes(root.resolve("sources/" + source.id() + "/original.wav")));
        assertThrows(IllegalArgumentException.class, () -> sources.audio("../outside"));
    }
    @Test void rejectsUnsupportedEmptyAndOversizedUploads() {
        assertThrows(IllegalArgumentException.class, () -> sources.upload("file.txt", 1, new ByteArrayInputStream(new byte[1])));
        assertThrows(IllegalArgumentException.class, () -> sources.upload("file.wav", 101L * 1024 * 1024, InputStream.nullInputStream()));
        assertThrows(IllegalArgumentException.class, () -> sources.upload("file.wav", 0, InputStream.nullInputStream()));
    }
    @Test void rejectsFakeAudioAndOutOfRangeDuration() throws Exception {
        Assumptions.assumeTrue(available("ffmpeg") && available("ffprobe"));
        assertThrows(IllegalArgumentException.class, () -> sources.upload("fake.wav", 4, new ByteArrayInputStream(new byte[4])));
        byte[] audio = wave(2);
        assertThrows(IllegalArgumentException.class, () -> sources.upload("short.wav", audio.length, new ByteArrayInputStream(audio)));
        try (var files = Files.walk(root)) {
            assertFalse(files.anyMatch(p -> p.toString().endsWith(".wav")));
        }
    }
}
