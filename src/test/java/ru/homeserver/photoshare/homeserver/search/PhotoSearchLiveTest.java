package ru.homeserver.photoshare.homeserver.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import ru.homeserver.photoshare.homeserver.service.FileService;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in test using real local models, isolated from the user's photo collection. */
@EnabledIfEnvironmentVariable(named = "PHOTO_SEARCH_LIVE_TEST", matches = "true")
class PhotoSearchLiveTest {
    @TempDir Path temp;

    @Test
    void realModelsDescribeIndexSearchAndServeImageThroughController() throws Exception {
        var app = new AppProperties();
        Path root = Files.createDirectory(temp.resolve("photos"));
        app.setStorageRoot(root.toString());
        app.setMetadataCacheDir(temp.resolve("cache").toString());
        // Make the fixture deterministic regardless of installed conversion tools.
        app.setMagickPath(temp.resolve("missing-magick").toString());
        var image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 640, 480);
            graphics.setColor(Color.RED);
            graphics.fillOval(70, 130, 180, 180);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(350, 130, 200, 180);
        } finally { graphics.dispose(); }
        Path photo = root.resolve("shapes.jpg");
        ImageIO.write(image, "jpeg", photo.toFile());
        var properties = new PhotoSearchProperties();
        var ai = new OllamaPhotoAiClient(properties);
        var reader = new PhotoImageReader(app);
        var mapper = new ObjectMapper();
        long started = System.nanoTime();
        try (var close = new ServiceHolder(new PhotoSearchService(new FileService(app), app, properties, ai, reader))) {
            var service = close.service;
            var mvc = MockMvcBuilders.standaloneSetup(new PhotoSearchController(service)).build();
            mvc.perform(post("/api/photo-search/index")).andExpect(status().isOk());
            await().atMost(Duration.ofSeconds(200)).until(() -> !service.status().running());
            assertNull(service.status().error(), () -> service.status().toString());
            assertEquals(1, service.status().available());
            var result = mvc.perform(get("/api/photo-search").param("query", "красный круг и синий прямоугольник"))
                    .andExpect(status().isOk()).andExpect(jsonPath("total").value(1)).andReturn();
            var page = mapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            var hit = page.path("hits").path(0);
            String description = hit.path("description").asText();
            assertTrue(description.matches("(?s).*[А-Яа-яЁё].*"), description);
            mvc.perform(get(hit.path("item").path("thumbnailUrl").asText()))
                    .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
            properties.setMinScore(-1);
            double unrelated = service.search("собака бежит по снежному лесу", 0, 60).hits().getFirst().score();
            double matching = hit.path("score").asDouble();
            assertTrue(matching > unrelated, "Matching=" + matching + ", unrelated=" + unrelated);
            System.out.printf("LIVE PHOTO SEARCH: caption=%s; matching=%.4f; unrelated=%.4f; elapsed=%.1fs%n",
                    description, matching, unrelated, (System.nanoTime() - started) / 1_000_000_000.0);
            mvc.perform(post("/api/photo-search/index")).andExpect(status().isOk());
            await().atMost(Duration.ofSeconds(5)).until(() -> !service.status().running());
            assertEquals(1, service.status().unchanged());
            try (var restored = new ServiceHolder(new PhotoSearchService(new FileService(app), app, properties, ai, reader))) {
                assertEquals(1, restored.service.status().available());
            }
        }
    }

    private record ServiceHolder(PhotoSearchService service) implements AutoCloseable {
        public void close() { service.close(); }
    }
}
