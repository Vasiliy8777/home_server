package ru.homeserver.photoshare.homeserver.music;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in check against the installed local engine; all artifacts stay in muz_gen. */
@EnabledIfEnvironmentVariable(named = "MUSIC_LIVE_TEST", matches = "true")
class MusicLiveTest {
    @Test void generatesShortAudioThroughTheRealJavaClient() throws Exception {
        var properties = new MusicProperties();
        properties.setAutoStart(false);
        Path originalRoot = Path.of(properties.getRoot());
        // A running cloud must not classify this test's jobs as abandoned after restart.
        properties.setRoot(originalRoot.resolve("live-checks").resolve(java.util.UUID.randomUUID().toString()).toString());
        var client = new LocalMusicClient(properties, new ObjectMapper());
        assertTrue(client.available());
        var service = new MusicService(properties, new ObjectMapper(), client);
        try {
            var request = new MusicRequest("Soft melodic RnB, warm female vocal, electric piano, gentle drums",
                    "[Verse]\nНа берегу, где ветер играет,\nМоре мечту мою сохраняет.\n[Chorus]\nРисунки на песке,\nОстанься в моём сердце.",
                    "acestep-v15-xl-turbo", "ru", 10, 90, 42L, 8, false, true, false);
            String originalJob = System.getenv("MUSIC_LIVE_JOB");
            if (originalJob != null && !originalJob.isBlank()) {
                assertTrue(originalJob.matches("[a-fA-F0-9-]{36}"));
                request = new ObjectMapper().readValue(originalRoot.resolve("jobs").resolve(originalJob + ".json").toFile(),
                        MusicService.Job.class).request();
                assertNotNull(request, "The selected job must contain a music request");
            }
            String mode = System.getenv("MUSIC_LIVE_MODE");
            if ("speech".equals(mode) || "speech_clone".equals(mode)) {
                Path runtime = Path.of(properties.getRoot()).resolve("runtime");
                Files.createDirectories(runtime);
                Files.copy(originalRoot.resolve("runtime/generate_speech.py"), runtime.resolve("generate_speech.py"));
                request = new MusicRequest("A warm low male voice, calm confident delivery, clear Russian pronunciation, natural pauses. Speak, do not sing.",
                        "Добрый вечер! Это проверка локальной озвучки. Каждое слово должно прозвучать чётко и спокойно.",
                        null, "ru", 0, null, 42L, 0, false, false, false,
                        "speech", "", "", null, null, null);
                if ("speech_clone".equals(mode)) {
                    Path sample = Path.of(System.getenv("MUSIC_SAMPLE_PATH"));
                    var sources = new MusicSourceService(properties, new ObjectMapper());
                    MusicSourceService.Source source;
                    try (var input = Files.newInputStream(sample)) {
                        source = sources.upload(sample.getFileName().toString(), Files.size(sample), input, "voice");
                    }
                    request = new MusicRequest("", "Теперь этот голос произносит новый текст. Проверяем перенос тембра и чёткую речь.",
                            null, "ru", 0, null, 42L, 0, false, false, false,
                            "speech_clone", source.id(), "", null, null, null,
                            System.getenv("MUSIC_REFERENCE_TEXT"), Boolean.parseBoolean(System.getenv("MUSIC_CLONE_VOICE_ONLY")));
                }
            }
            if ("cover".equals(mode) || "repaint".equals(mode)) {
                Path sample = Path.of(System.getenv("MUSIC_SAMPLE_PATH"));
                var sources = new MusicSourceService(properties, new ObjectMapper());
                MusicSourceService.Source source;
                try (var input = Files.newInputStream(sample)) {
                    source = sources.upload(sample.getFileName().toString(), Files.size(sample), input);
                }
                request = new MusicRequest("Soft acoustic RnB, female vocal, acoustic guitar, gentle percussion", request.lyrics(),
                        request.model(), request.language(), (int)Math.ceil(source.duration()), 90, 42L, 8, false, false, false,
                        mode, source.id(), "Replace electric piano with acoustic guitar, retain the melody", .6,
                        "repaint".equals(mode) ? 2. : null, "repaint".equals(mode) ? 8. : null);
            }
            var job = service.generate(request);
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20);
            String lastMessage = "";
            while (System.nanoTime() < deadline) {
                job = service.get(job.id());
                if (!job.message().equals(lastMessage)) { System.out.println(job.id() + ": " + job.message()); lastMessage = job.message(); }
                if (job.state().equals("failed")) fail(job.message());
                if (job.state().equals("ready")) {
                    assertTrue(Files.size(service.audio(job.id())) > 44);
                    try (var stream = Files.newInputStream(service.audio(job.id()))) {
                        byte[] header = stream.readNBytes(12);
                        assertEquals("RIFF", new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
                        assertEquals("WAVE", new String(header, 8, 4, java.nio.charset.StandardCharsets.US_ASCII));
                    }
                    System.out.println("Audio verified: " + service.audio(job.id()));
                    if ("speech".equals(mode) || "speech_clone".equals(mode)) {
                        assertTrue(Files.isRegularFile(service.audio(job.id()).resolveSibling("speech-parts.json")));
                        assertEquals(request.lyrics(), Files.readString(Path.of(properties.getRoot()).resolve("speech-texts/" + job.id() + ".txt")));
                    }
                    if ("speech_clone".equals(mode)) assertTrue(Files.isRegularFile(service.audio(job.id()).resolveSibling("voice-reference.wav")));
                    return;
                }
                Thread.sleep(3000);
            }
            fail("Music generation timed out");
        } finally { service.close(); }
    }
}
