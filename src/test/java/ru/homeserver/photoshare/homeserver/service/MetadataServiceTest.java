package ru.homeserver.photoshare.homeserver.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.homeserver.photoshare.homeserver.config.AppProperties;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MetadataServiceTest {
    @TempDir Path temp;

    private AppProperties properties() {
        var app = new AppProperties();
        app.setMetadataCacheDir(temp.resolve(".metadata_cache").toString());
        app.getCache().setDatabasePath(temp.resolve("catalog.sqlite").toString());
        return app;
    }

    @Test
    void statisticsSkipHiddenSubtreesAndRefreshAfterInvalidation() throws Exception {
        var service = new MetadataService(properties());
        Path folder = Files.createDirectory(temp.resolve("photos"));
        Files.writeString(folder.resolve("visible.txt"), "123");
        Files.writeString(Files.createDirectories(folder.resolve("Bitcoin/nested")).resolve("hidden.txt"), "hidden");
        var stats = service.readFolderStats(folder);
        assertEquals(1L, stats.get("fileCount"));
        assertEquals(3L, stats.get("size"));
        assertEquals(0L, stats.get("folderCount"));
        Files.writeString(folder.resolve("another.txt"), "12");
        service.invalidate(folder);
        assertEquals(2L, service.readFolderStats(folder).get("fileCount"));
    }

    @Test
    void changedSizeInvalidatesMemoryAndDatabaseMetadata() throws Exception {
        var app = properties();
        var database = new SqliteCacheStore(app);
        try {
            var service = new MetadataService(app, database);
            Path file = temp.resolve("sample.txt");
            Files.writeString(file, "abc");
            var modified = Files.getLastModifiedTime(file);
            assertEquals(3L, service.readFileProperties(file).get("size"));
            Files.writeString(file, "abcdef");
            Files.setLastModifiedTime(file, modified);
            assertEquals(6L, service.readFileProperties(file).get("size"));
            var restored = new MetadataService(app, database);
            assertEquals(6, ((Number) restored.readFileProperties(file).get("size")).longValue());
        } finally { database.close(); }
    }
}
