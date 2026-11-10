package ru.homeserver.photoshare.homeserver.music;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MusicServiceTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private LocalMusicClient client;
    private MusicService service;
    private MusicProperties properties;

    @BeforeEach void setup() {
        properties = new MusicProperties();
        properties.setRoot(root.toString());
        properties.setAutoStart(false);
        client = mock(LocalMusicClient.class);
        service = new MusicService(properties, json, client);
    }
    @AfterEach void close() { service.close(); }
    private MusicRequest request() {
        return new MusicRequest("Тёплый инди-поп", "[Verse]\nДорога к морю", "acestep-v15-xl-turbo", "ru", 60, 100, 42L, 8, false, true, false);
    }
    private MusicService.Job finished(String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var job = service.get(id);
            if (!job.state().equals("running") && !job.state().equals("queued")) return job;
            Thread.sleep(10);
        }
        throw new AssertionError("Job did not finish");
    }
    private void audioResponse() throws Exception {
        when(client.submit(any())).thenReturn("ace-task");
        var result = json.createArrayNode().add(json.createObjectNode()
                .put("file", "/v1/audio?path=audio.wav").put("seed_value", "42"));
        var response = json.createObjectNode().put("status", 1).put("result", json.writeValueAsString(result));
        when(client.result("ace-task")).thenReturn(response);
        doAnswer(call -> { Files.write(call.getArgument(1, Path.class), new byte[100]); return null; })
                .when(client).download(anyString(), any());
    }

    @Test void createsTemporaryAudioAndPersistsPromptsThenSavesIdempotentlyAcrossRestart() throws Exception {
        audioResponse();
        var started = service.generate(request());
        var ready = finished(started.id());
        assertEquals("ready", ready.state());
        assertEquals("Тёплый инди-поп", Files.readString(root.resolve("music-prompts/" + ready.id() + ".txt")));
        assertEquals(request().lyrics(), Files.readString(root.resolve("lyrics/" + ready.id() + "-input.txt")));
        assertTrue(service.audio(ready.id()).startsWith(root.resolve("tmp")));
        verify(client).unloadOllama();
        var saved = service.save(ready.id());
        assertEquals("saved", saved.state());
        assertEquals(root.resolve("song-" + saved.id() + ".wav"), service.audio(saved.id()));
        assertFalse(Files.exists(root.resolve("tmp/" + saved.id() + "/result.wav")));
        assertEquals(saved, service.save(saved.id()));
        assertThrows(IllegalStateException.class, () -> service.delete(saved.id()));
        try (var restarted = new Restarted(properties, json, client)) {
            assertEquals(saved, restarted.service.get(saved.id()));
            assertEquals(saved, restarted.service.history().getFirst());
            assertTrue(Files.exists(restarted.service.audio(saved.id())));
        }
    }

    @Test void deletesOnlyTemporaryAudioAndKeepsPromptHistory() throws Exception {
        audioResponse();
        var ready = finished(service.generate(request()).id());
        Path audio = service.audio(ready.id());
        var deleted = service.delete(ready.id());
        assertEquals("deleted", deleted.state());
        assertFalse(Files.exists(audio));
        assertTrue(Files.exists(root.resolve("music-prompts/" + ready.id() + ".txt")));
        assertThrows(NoSuchFileException.class, () -> service.audio(ready.id()));
        assertThrows(IllegalStateException.class, () -> service.save(ready.id()));
        assertThrows(IllegalArgumentException.class, () -> service.delete("../outside"));
    }

    @Test void lyricsKeepOriginalPromptAndGeneratedTextAndRejectConcurrentGeneration() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.lyrics(anyString(), anyString(), anyString())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout");
            return "[Chorus]\nМоре зовёт";
        });
        var job = service.generateLyrics(new MusicService.LyricsRequest("Песня про море", "Поп", "ru"));
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> service.generate(request()));
        } finally { release.countDown(); }
        var ready = finished(job.id());
        assertEquals("[Chorus]\nМоре зовёт", ready.lyrics());
        assertEquals("Песня про море", Files.readString(root.resolve("lyrics-prompts/" + job.id() + ".txt")));
        assertEquals(ready.lyrics(), Files.readString(root.resolve("lyrics/" + job.id() + ".txt")));
    }

    @Test void cudaFailureShowsAnActionableMessage() throws Exception {
        when(client.submit(any())).thenReturn("ace-task");
        when(client.result("ace-task")).thenReturn(json.createObjectNode()
                .put("status", 2).put("error", "CUDA out of memory. Tried to allocate 850 MiB"));
        var failed = finished(service.generate(request()).id());
        assertEquals("failed", failed.state());
        assertTrue(failed.message().contains("Недостаточно видеопамяти"));
    }

    @Test void engineFailureIsRecordedWithPrompts() throws Exception {
        when(client.submit(any())).thenThrow(new IOException("ACE-Step offline"));
        var failed = finished(service.generate(request()).id());
        assertEquals("failed", failed.state());
        assertTrue(failed.message().contains("ACE-Step offline"));
        assertTrue(Files.exists(root.resolve("music-prompts/" + failed.id() + ".txt")));
    }

    @Test void missingAutomaticLauncherProducesAnActionableFailureWithoutStartingAProcess() throws Exception {
        properties.setAutoStart(true);
        when(client.available()).thenReturn(false);
        var failed = finished(service.generate(request()).id());
        assertEquals("failed", failed.state());
        assertTrue(failed.message().contains("setup-music.ps1"));
        verify(client, never()).submit(any());
    }

    @Test void rejectsInvalidOptionsBeforeStartingWork() {
        assertEquals(8, new MusicRequest("Поп", "текст", "acestep-v15-xl-turbo", "ru", 60, null, null, 20, false, true, false).steps());
        assertEquals(8, new MusicRequest("Поп", "текст", "acestep-v15-turbo", "ru", 60, null, null, 20, false, true, false).steps());
        assertThrows(IllegalArgumentException.class, () -> request().withSeed(4294967296L));
        assertThrows(IllegalArgumentException.class, () -> new MusicRequest("", "", "../evil", "ru", 60, null, null, 8, false, true, false));
        assertThrows(IllegalArgumentException.class, () -> new MusicRequest("Поп", "текст", "acestep-v15-turbo", "ru", 600, null, null, 8, false, true, false));
        assertThrows(IllegalArgumentException.class, () -> new MusicRequest("Поп", "", "acestep-v15-turbo", "ru", 60, null, null, 8, false, true, false));
    }
    @Test void stagesAudioInsideEngineTempAndPersistsRequestedChanges() throws Exception {
        service.close();
        var sources = mock(MusicSourceService.class);
        service = new MusicService(properties, json, client, sources);
        String sourceId = java.util.UUID.randomUUID().toString();
        Path sourceFile = root.resolve("original.wav"); Files.write(sourceFile, new byte[100]);
        when(sources.get(sourceId)).thenReturn(new MusicSourceService.Source(sourceId, "sample.wav", 30, ""));
        when(sources.audio(sourceId)).thenReturn(sourceFile);
        audioResponse();
        when(client.submit(any(), any())).thenReturn("ace-task");
        var request = new MusicRequest("Acoustic piano", "", "acestep-v15-xl-turbo", "ru", 30, null, 42L, 8,
                true, false, false, "cover", sourceId, "Добавить пианино", .5, null, null);
        var ready = finished(service.generate(request).id());
        assertEquals("ready", ready.state());
        Path staged = root.resolve("tmp/ace-step/cloud-inputs/" + ready.id() + ".wav");
        assertArrayEquals(Files.readAllBytes(sourceFile), Files.readAllBytes(staged));
        verify(client).submit(eq(request), eq(staged));
        assertEquals("Добавить пианино", Files.readString(root.resolve("music-prompts/" + ready.id() + "-changes.txt")));
        service.delete(ready.id());
        assertFalse(Files.exists(staged));
        assertTrue(Files.exists(sourceFile));
    }
    @Test void rejectsInvalidSampleAndRepaintSettings() {
        assertThrows(IllegalArgumentException.class, () -> new MusicRequest("piano", "", "acestep-v15-turbo", "ru", 30, null, null, 8,
                false, false, false, "cover", "../source", "change", .5, null, null));
        assertThrows(IllegalArgumentException.class, () -> new MusicRequest("piano", "", "acestep-v15-turbo", "ru", 30, null, null, 8,
                false, false, false, "repaint", java.util.UUID.randomUUID().toString(), "change", .5, 10., 11.));
    }
    @Test void randomSeedIsRecordedAndCanBeReusedAfterRestart() throws Exception {
        audioResponse();
        var random = request().withSeed(42);
        random = new MusicRequest(random.description(), random.lyrics(), random.model(), random.language(),
                random.duration(), random.bpm(), null, random.steps(), false, true, false);
        var ready = finished(service.generate(random).id());
        assertNotNull(ready.request().seed());
        var sent = org.mockito.ArgumentCaptor.forClass(MusicRequest.class);
        verify(client).submit(sent.capture());
        assertEquals(ready.request().seed(), sent.getValue().seed());
        try (var restarted = new Restarted(properties, json, client)) {
            assertEquals(ready.request().seed(), restarted.service.get(ready.id()).request().seed());
        }
        assertEquals(ready.request().seed(), finished(service.generate(ready.request()).id()).request().seed());
    }

    @Test void recoversLegacySeedFromEngineResultMetadata() throws Exception {
        audioResponse();
        var ready = finished(service.generate(request()).id());
        var record = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(root.resolve("jobs/" + ready.id() + ".json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)record.path("request")).putNull("seed");
        json.writeValue(root.resolve("jobs/" + ready.id() + ".json").toFile(), record);
        assertEquals(42L, service.get(ready.id()).request().seed());
    }

    @Test void speechUsesExactTextAndExistingResultLifecycle() throws Exception {
        service.close();
        var speech = mock(LocalSpeechService.class);
        var sources = mock(MusicSourceService.class);
        service = new MusicService(properties, json, client, sources, speech);
        doAnswer(call -> {
            Path work = call.getArgument(1, Path.class); Files.createDirectories(work);
            Files.write(work.resolve("result.wav"), new byte[100]); return null;
        }).when(speech).generate(any(), any());
        var r = new MusicRequest("Спокойный низкий мужской голос", "Первая фраза.\nВторая фраза!", null, "ru", 0, null, 123L, 0,
                false, false, false, "speech", "", "", null, null, null);
        var ready = finished(service.generate(r).id());
        assertEquals("ready", ready.state());
        verify(speech).generate(eq(r), any()); verifyNoInteractions(sources);
        verify(client, never()).submit(any());
        assertEquals(r.lyrics(), Files.readString(root.resolve("speech-texts/" + ready.id() + ".txt")));
        assertEquals(r.description(), Files.readString(root.resolve("speech-prompts/" + ready.id() + ".txt")));
        assertTrue(Files.isRegularFile(service.audio(ready.id())));
        assertEquals("saved", service.save(ready.id()).state());
    }

    @Test void voiceCloningUsesValidatedSampleAndKeepsTranscriptWithSeed() throws Exception {
        service.close();
        var speech = mock(LocalSpeechService.class);
        var sources = mock(MusicSourceService.class);
        service = new MusicService(properties, json, client, sources, speech);
        String id = java.util.UUID.randomUUID().toString();
        Path sample = root.resolve("voice.wav"); Files.write(sample, new byte[100]);
        when(sources.get(id)).thenReturn(new MusicSourceService.Source(id, "voice.wav", 5, "/audio", "voice"));
        when(sources.audio(id)).thenReturn(sample);
        doAnswer(call -> {
            Path work = call.getArgument(1, Path.class); Files.createDirectories(work);
            Files.write(work.resolve("result.wav"), new byte[100]); return null;
        }).when(speech).generate(any(), any(), eq(sample));
        var r = new MusicRequest("", "Новый текст для произнесения.", null, "ru", 0, null, null, 0,
                false, false, false, "speech_clone", id, "", null, null, null, "Слова в образце.", false);
        var ready = finished(service.generate(r).id());
        assertEquals("ready", ready.state());
        assertEquals("Qwen3-TTS-12Hz-1.7B-Base", ready.request().model());
        assertNotNull(ready.request().seed());
        assertEquals("Слова в образце.", ready.request().referenceText());
        verify(speech).generate(eq(ready.request()), any(), eq(sample));
        verify(client, never()).submit(any());
        assertThrows(IllegalArgumentException.class, () -> new MusicRequest("", "Новый текст", null, "ru", 0, null, null, 0,
                false, false, false, "speech_clone", id, "", null, null, null, "", false));
        when(sources.get(id)).thenReturn(new MusicSourceService.Source(id, "music.wav", 15, "/audio", "music"));
        assertThrows(IllegalArgumentException.class, () -> service.generate(r));
        service.delete(ready.id());
        assertTrue(Files.exists(sample));
    }

    private static class Restarted implements AutoCloseable {
        final MusicService service;
        Restarted(MusicProperties p, ObjectMapper j, LocalMusicClient c) { service = new MusicService(p, j, c); }
        public void close() { service.close(); }
    }
}
