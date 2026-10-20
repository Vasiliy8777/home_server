package ru.homeserver.photoshare.homeserver.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import ru.homeserver.photoshare.homeserver.dto.FileItemDto;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqliteCacheStoreTest {
    @TempDir Path temp;

    private AppProperties properties() {
        var app = new AppProperties();
        app.setMetadataCacheDir(temp.toString());
        app.getCache().setDatabasePath(temp.resolve("catalog.sqlite").toString());
        return app;
    }

    private FileItemDto item(String name, boolean directory, long size, long created) {
        return new FileItemDto(name, name, directory, size, directory ? "directory" : "image",
                null, null, null, created, created, null, null, false, null, null);
    }

    @Test
    void persistsPagesAndValidatesMetadataVersion() throws Exception {
        AppProperties app = properties();
        try (var store = new CloseStore(new SqliteCacheStore(app))) {
            store.value.saveFolder("root", "v1", List.of(item("b.jpg", false, 200, 20),
                    item("folder", true, 0, 0), item("a.jpg", false, 100, 30)));
            assertEquals("folder", store.value.page("root", "name", "asc", 0, 1).getFirst().name());
            assertEquals("a.jpg", store.value.page("root", "name", "asc", 1, 1).getFirst().name());
            assertEquals("b.jpg", store.value.page("root", "size", "desc", 0, 1).getFirst().name());
            assertEquals("b.jpg", store.value.page("root", "lastModified", "asc", 1, 1).getFirst().name());
            assertTrue(store.value.page("root", "name", "asc", 30, 1).isEmpty());
            store.value.saveMetadata(temp.resolve("a.jpg"), 10, 100, Map.of("device", "camera"));
            assertNotNull(store.value.metadata(temp.resolve("a.jpg"), 10, 100));
            assertNull(store.value.metadata(temp.resolve("a.jpg"), 10, 101));
        }
        try (var restored = new CloseStore(new SqliteCacheStore(app))) {
            assertEquals("v1", restored.value.signature("root"));
            assertEquals(3, restored.value.count("root"));
        }
    }

    @Test
    void pagingSnapshotSurvivesRefreshAndReleasedSnapshotIsRemoved() throws Exception {
        try (var store = new CloseStore(new SqliteCacheStore(properties()))) {
            store.value.saveFolder("root", "v1", List.of(item("old.jpg", false, 1, 1)));
            String snapshot = store.value.acquireSnapshot("root");
            store.value.invalidateFolder("root");
            store.value.saveFolder("root", "v2", List.of(item("new.jpg", false, 2, 2)));
            assertEquals("old.jpg", store.value.page(snapshot, "name", "asc", 0, 10).getFirst().name());
            assertEquals("new.jpg", store.value.page("root", "name", "asc", 0, 10).getFirst().name());
            store.value.releaseSnapshot(snapshot);
            assertEquals(0, store.value.count(snapshot));
        }
    }

    @Test
    void storesVectorsAsFloat32AndKeepsDifferentStorageRootsSeparate() throws Exception {
        try (var store = new CloseStore(new SqliteCacheStore(properties()))) {
            var entry = new ru.homeserver.photoshare.homeserver.search.PhotoSearchService.Entry("photo.jpg", 10, 20,
                    "model-v1", "Описание", new float[]{0.25f, 0.75f});
            store.value.savePhoto("root-a", entry);
            assertArrayEquals(entry.vector(), store.value.photos("root-a").getFirst().vector());
            assertTrue(store.value.photos("root-b").isEmpty());
            assertFalse(store.value.photosImported("root-a"));
            store.value.markPhotosImported("root-a");
            assertTrue(store.value.photosImported("root-a"));
            store.value.removePhoto("root-a", "photo.jpg");
            assertTrue(store.value.photos("root-a").isEmpty());
        }
    }

    private record CloseStore(SqliteCacheStore value) implements AutoCloseable { public void close() { value.close(); } }
}
