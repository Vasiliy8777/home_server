package ru.homeserver.photoshare.homeserver.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.homeserver.photoshare.homeserver.search.PhotoSearchService;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class CacheWiringTest {
    private static final Path ROOT = Path.of("build", "cache-wiring", UUID.randomUUID().toString()).toAbsolutePath();

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry properties) {
        properties.add("server.ssl.enabled", () -> false);
        properties.add("server.ssl.key-store-password", () -> "test");
        properties.add("app.security.username", () -> "test");
        properties.add("app.security.password", () -> "test");
        properties.add("app.storage-root", () -> ROOT.resolve("photos").toString());
        properties.add("app.metadata-cache-dir", () -> ROOT.resolve("metadata").toString());
        properties.add("app.folder-cache-dir", () -> ROOT.resolve("folders").toString());
        properties.add("app.thumbnail-cache-dir", () -> ROOT.resolve("thumbnails").toString());
        properties.add("app.preview-cache-dir", () -> ROOT.resolve("previews").toString());
        properties.add("app.upload-temp-dir", () -> ROOT.resolve("uploads").toString());
        properties.add("app.video.hls-cache-dir", () -> ROOT.resolve("hls").toString());
        properties.add("app.log-dir", () -> ROOT.resolve("logs").toString());
        properties.add("app.cache.database-enabled", () -> true);
        properties.add("app.cache.database-path", () -> ROOT.resolve("catalog.sqlite").toString());
    }

    @Autowired SqliteCacheStore database;
    @Autowired FolderPrepareService folders;
    @Autowired PhotoSearchService photos;

    @Test
    void applicationWiresDatabaseIntoFolderAndPhotoServices() {
        assertTrue(database.enabled());
        assertEquals(0, photos.status().available());
        String job = folders.start("", "name", "asc", "all", false, null, null);
        await().atMost(Duration.ofSeconds(5)).until(() -> folders.get(job).complete);
        assertNotNull(folders.get(job).databaseFolder);
        assertEquals(0, folders.get(job).total);
    }
}
