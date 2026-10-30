package ru.homeserver.photoshare.homeserver.music;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

class LocalMusicClientTest {
    @TempDir Path directory;
    HttpServer server;
    LocalMusicClient client;
    MusicProperties properties;
    ObjectMapper json = new ObjectMapper();
    ConcurrentHashMap<String, JsonNode> calls = new ConcurrentHashMap<>();
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // Uvicorn does not implement Java's default clear-text HTTP/2 upgrade.
            if (exchange.getRequestHeaders().getFirst("Upgrade") != null) {
                byte[] error = "Unsupported upgrade request".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, error.length);
                try (var output = exchange.getResponseBody()) { output.write(error); }
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod())) calls.put(path, json.readTree(exchange.getRequestBody()));
            String text = switch (path) {
                case "/release_task" -> "{\"code\":200,\"data\":{\"task_id\":\"task-1\"}}";
                case "/query_result" -> "{\"code\":200,\"data\":[{\"task_id\":\"task-1\",\"status\":0}]}";
                case "/api/chat" -> "{\"message\":{\"content\":\"[Verse] Море\"}}";
                case "/api/ps" -> "{\"models\":[{\"name\":\"qwen3:14b\"}]}";
                case "/reject" -> "{\"detail\":\"Duration is outside the allowed range\"}";
                default -> "{}";
            };
            byte[] bytes = path.equals("/v1/audio") ? new byte[100] : text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(path.equals("/reject") ? 400 : 200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        properties = new MusicProperties();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.setAceUrl(base); properties.setOllamaUrl(base);
        client = new LocalMusicClient(properties, json);
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void mapsGenerationOptionsAndPollsDocumentedEnvelope() throws Exception {
        var request = new MusicRequest("Rock", "unused", "acestep-v15-xl-turbo", "ru", 90, 120, 77L, 8, true, true, false);
        assertEquals("task-1", client.submit(request));
        var body = calls.get("/release_task");
        assertEquals("[Instrumental]", body.path("lyrics").asText());
        assertEquals(1, body.path("batch_size").asInt());
        assertEquals("wav", body.path("audio_format").asText());
        assertEquals("acestep-5Hz-lm-1.7B", body.path("lm_model_path").asText());
        assertFalse(body.path("use_random_seed").asBoolean());
        assertEquals(77, body.path("seed").asLong());
        assertEquals(0, client.result("task-1").path("status").asInt());
        assertEquals("task-1", calls.get("/query_result").path("task_id_list").get(0).asText());
    }
    @Test void lyricsUnloadAfterResponseAndReturnOnlyModelContent() throws Exception {
        assertEquals("[Verse] Море", client.lyrics("про море", "rock", "ru"));
        assertEquals(0, calls.get("/api/chat").path("keep_alive").asInt());
        assertFalse(calls.get("/api/chat").path("think").asBoolean());
        client.unloadOllama();
        assertEquals("qwen3:14b", calls.get("/api/generate").path("model").asText());
    }
    @Test void includesEngineErrorDetailsInsteadOfOnlyHttpStatus() {
        IOException error = assertThrows(IOException.class, () -> client.post(properties.getAceUrl(), "/reject", java.util.Map.of()));
        assertTrue(error.getMessage().contains("HTTP 400"));
        assertTrue(error.getMessage().contains("Duration is outside the allowed range"));
    }
    @Test void sendsSourceAndEditingControlsWithoutMusicalPlanning() throws Exception {
        Path source = directory.resolve("source.wav");
        String sourceId = java.util.UUID.randomUUID().toString();
        var request = new MusicRequest("piano", "", "acestep-v15-xl-turbo", "ru", 30, null, 42L, 8,
                true, true, true, "repaint", sourceId, "soft drums", .6, 2., 8.);
        assertEquals("task-1", client.submit(request, source));
        var body = calls.get("/release_task");
        assertEquals("repaint", body.path("task_type").asText());
        assertEquals(source.toAbsolutePath().toString(), body.path("src_audio_path").asText());
        assertEquals(2., body.path("repainting_start").asDouble());
        assertEquals(8., body.path("repainting_end").asDouble());
        assertEquals("explicit", body.path("chunk_mask_mode").asText());
        assertTrue(body.path("prompt").asText().contains("soft drums"));
        assertFalse(body.path("thinking").asBoolean());
        assertFalse(body.path("use_cot_caption").asBoolean());
    }
    @Test void acceptsOnlyLocalAudioEndpointAndRejectsRemoteServiceConfiguration() throws Exception {
        client.download("/v1/audio?path=test.wav", directory.resolve("audio.wav"));
        assertEquals(100, Files.size(directory.resolve("audio.wav")));
        assertThrows(IOException.class, () -> client.download("https://example.com/audio.wav", directory.resolve("bad.wav")));
        assertThrows(IOException.class, () -> client.download("/api/chat", directory.resolve("bad.wav")));
        properties.setAceUrl("https://example.com");
        assertThrows(IllegalArgumentException.class, () -> client.result("task"));
    }
    @Test void sourceCleanupIsConfinedToMusicEngineTemporaryDirectory() throws Exception {
        properties.setRoot(directory.toString());
        Path inside = directory.resolve("tmp/ace-step/api_audio/result.wav");
        Files.createDirectories(inside.getParent());
        Files.write(inside, new byte[100]);
        Path outside = directory.resolve("saved.wav");
        Files.write(outside, new byte[100]);
        client.removeTemporarySource("/v1/audio?path=" + java.net.URLEncoder.encode(inside.toString(), java.nio.charset.StandardCharsets.UTF_8));
        client.removeTemporarySource("/v1/audio?path=" + java.net.URLEncoder.encode(outside.toString(), java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(Files.exists(inside));
        assertTrue(Files.exists(outside));
    }
}
