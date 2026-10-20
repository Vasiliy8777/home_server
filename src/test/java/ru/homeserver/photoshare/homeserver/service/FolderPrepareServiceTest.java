package ru.homeserver.photoshare.homeserver.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import ru.homeserver.photoshare.homeserver.share.ShareService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FolderPrepareServiceTest {
    @TempDir Path temp;

    private AppProperties properties(boolean database) throws Exception {
        var app = new AppProperties();
        app.setStorageRoot(Files.createDirectories(temp.resolve("photos")).toString());
        app.setFolderCacheDir(temp.resolve("folders").toString());
        app.setMetadataCacheDir(temp.resolve("metadata").toString());
        app.getCache().setDatabaseEnabled(database);
        app.getCache().setDatabasePath(temp.resolve("catalog.sqlite").toString());
        return app;
    }

    @Test
    void listsFolderOnlyOnceOnCacheMissAndImportsLegacyCache() throws Exception {
        var app = properties(false);
        var files = spy(new FileService(app));
        for (int i = 0; i < 450; i++) Files.writeString(files.getRootPath().resolve("photo" + i + ".jpg"), "test");
        var metadata = mock(MetadataService.class);
        var thumbnails = mock(ThumbnailService.class);
        var shares = mock(ShareService.class);
        var legacy = new FolderPrepareService(files, metadata, thumbnails, app, shares);
        try { legacy.prepareFolderCacheOnly(""); }
        finally { legacy.close(); }
        verify(files, times(1)).list("", 0, Integer.MAX_VALUE);
        app.getCache().setDatabaseEnabled(true);
        var database = new SqliteCacheStore(app);
        var service = new FolderPrepareService(files, metadata, thumbnails, app, shares, database);
        try {
            clearInvocations(files, metadata);
            String id = service.start("", "name", "asc", "all", false, null, null);
            await().atMost(Duration.ofSeconds(5)).until(() -> service.get(id).complete);
            assertEquals(450, service.get(id).total);
            assertEquals(100, service.items(id, 0, 100).size());
            assertTrue(service.get(id).items.isEmpty());
            verify(files, never()).list(anyString(), anyInt(), anyInt());
            verifyNoInteractions(metadata);
        } finally { service.close(); database.close(); }
    }

    @Test
    void makesNamePageAvailableBeforeSlowMetadataFinishes() throws Exception {
        var app = properties(true);
        var files = new FileService(app);
        Files.writeString(files.getRootPath().resolve("photo.jpg"), "test");
        var metadata = mock(MetadataService.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(metadata.readCreatedAtFromPropertiesCache(any())).thenAnswer(call -> { entered.countDown(); release.await(); return 123L; });
        var database = new SqliteCacheStore(app);
        var service = new FolderPrepareService(files, metadata, mock(ThumbnailService.class), app, mock(ShareService.class), database);
        try {
            String id = service.start("", "name", "asc", "all", false, null, null);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(service.get(id).ready);
            assertFalse(service.get(id).complete);
            assertEquals("photo.jpg", service.items(id, 0, 100).getFirst().name());
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> service.invalidateFolderCache(""));
            release.countDown();
            await().atMost(Duration.ofSeconds(5)).until(() -> service.get(id).complete);
            assertEquals(123, service.items(id, 0, 100).getFirst().createdAt());
        } finally { release.countDown(); service.close(); database.close(); }
    }

    @Test
    void coalescesMultipleScheduledRefreshesOfSameFolder() throws Exception {
        var app = properties(false);
        var files = new FileService(app);
        var service = spy(new FolderPrepareService(files, mock(MetadataService.class), mock(ThumbnailService.class), app, mock(ShareService.class)));
        try {
            doNothing().when(service).prepareFolderCacheOnly(anyString());
            for (int i = 0; i < 20; i++) service.refreshFolderCacheAsync("");
            verify(service, timeout(3000).times(1)).prepareFolderCacheOnly("");
        } finally { service.close(); }
    }
}
