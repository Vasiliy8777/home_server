package ru.homeserver.photoshare.homeserver.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class OllamaPhotoAiClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, JsonNode> requests = new ConcurrentHashMap<>();
    private HttpServer server;
    private OllamaPhotoAiClient client;
    private volatile int responseCode = 200;
    private volatile String embeddingResponse = "{\"embeddings\":[[3,4]]}";
    private volatile String chatResponse = "{\"message\":{\"content\":\"Дети на пляже\"}}";
    private final java.util.concurrent.atomic.AtomicInteger chatCalls = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean emptyFirst;

    @BeforeEach
    void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.put(path, mapper.readTree(exchange.getRequestBody()));
            String body = embeddingResponse;
            if (path.endsWith("chat")) {
                body = emptyFirst && chatCalls.incrementAndGet() == 1
                        ? "{\"message\":{\"content\":\"\",\"thinking\":\"reasoning\"},\"done_reason\":\"length\",\"eval_count\":512}"
                        : chatResponse;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseCode, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        var properties = new PhotoSearchProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client = new OllamaPhotoAiClient(properties);
    }

    @AfterEach
    void cleanup() { server.stop(0); }

    @Test
    void sendsImageAndParsesNonStreamingDescription() throws Exception {
        byte[] image = {1, 2, 3};
        assertEquals("Дети на пляже", client.describe(image));
        JsonNode request = requests.get("/api/chat");
        assertEquals("qwen3-vl:4b", request.path("model").asText());
        assertFalse(request.path("stream").asBoolean());
        assertFalse(request.path("think").asBoolean());
        assertEquals(Base64.getEncoder().encodeToString(image),
                request.path("messages").path(0).path("images").path(0).asText());
    }

    @Test
    void retriesEmptyThinkingOnlyResponseWithLargerBudget() throws Exception {
        emptyFirst = true;
        assertEquals("Дети на пляже", client.describe(new byte[]{1}));
        assertEquals(2, chatCalls.get());
        assertEquals(2048, requests.get("/api/chat").path("options").path("num_predict").asInt());
    }

    @Test
    void reportsWhyCaptionIsEmptyAfterBoundedRetry() {
        chatResponse = "{\"message\":{\"content\":\"\",\"thinking\":\"reasoning\"},\"done_reason\":\"length\",\"eval_count\":1024}";
        IOException failure = assertThrows(IOException.class, () -> client.describe(new byte[]{1}));
        assertTrue(failure.getMessage().contains("done_reason=length"));
        assertTrue(failure.getMessage().contains("thinkingChars=9"));
    }

    @Test
    void usesRetrievalPrefixesAndNormalizesVectors() throws Exception {
        assertArrayEquals(new float[]{0.6f, 0.8f}, client.embed("пляж", true), 0.00001f);
        assertEquals("task: search result | query: пляж", requests.get("/api/embed").path("input").asText());
        client.embed("Дети у моря", false);
        assertEquals("title: Фото | text: Дети у моря", requests.get("/api/embed").path("input").asText());
        assertFalse(requests.get("/api/embed").path("truncate").asBoolean());
    }

    @Test
    void reportsProviderErrorsAndRejectsInvalidVectorResponses() {
        responseCode = 404;
        assertThrows(PhotoAiClient.UnavailableException.class, () -> client.describe(new byte[]{1}));
        responseCode = 200;
        embeddingResponse = "{\"embeddings\":[[0,0]]}";
        assertThrows(IOException.class, () -> client.embed("фото", true));
        embeddingResponse = "{\"embeddings\":[[\"bad\"]]}";
        assertThrows(IOException.class, () -> client.embed("фото", true));
    }
}
