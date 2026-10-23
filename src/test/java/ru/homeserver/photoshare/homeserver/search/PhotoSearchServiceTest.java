package ru.homeserver.photoshare.homeserver.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import ru.homeserver.photoshare.homeserver.service.FileService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PhotoSearchServiceTest {
    @TempDir Path temp;
    private Path root;
    private AppProperties app;
    private PhotoSearchProperties properties;
    private FakeAi ai;
    private PhotoImageReader reader;
    private final List<PhotoSearchService> services = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        root = Files.createDirectory(temp.resolve("storage"));
        app = new AppProperties();
        app.setStorageRoot(root.toString());
        app.setMetadataCacheDir(root.resolve(".metadata_cache").toString());
        properties = new PhotoSearchProperties();
        ai = new FakeAi();
        reader = mock(PhotoImageReader.class);
        when(reader.readJpeg(any())).thenAnswer(call -> Files.readAllBytes(call.getArgument(0)));
    }

    @AfterEach
    void cleanup() { services.forEach(PhotoSearchService::close); }

    private PhotoSearchService service() throws Exception {
        var service = new PhotoSearchService(new FileService(app), app, properties, ai, reader);
        services.add(service);
        return service;
    }

    private void index(PhotoSearchService service) {
        service.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> !service.status().running());
        assertNull(service.status().error());
    }

    @Test
    void indexesOnlyPhotosAndSkipsAllExcludedSubtrees() throws Exception {
        Files.writeString(root.resolve("beach.jpg"), "beach");
        Files.writeString(root.resolve("video.mp4"), "beach");
        Files.writeString(root.resolve("notes.txt"), "beach");
        for (String name : new String[]{".logs", "$RECYCLE.BIN", "System Volume Information",
                "Bitcoin", "BitcoinCoreSpeedCheck", "BitcoinJavaNode", ".thumbnails"}) {
            Path folder = Files.createDirectories(root.resolve(name).resolve("nested"));
            Files.writeString(folder.resolve("private.jpg"), "private");
        }
        var service = service();
        index(service);
        assertEquals(1, service.status().indexed());
        assertEquals(1, service.status().available());
        verify(reader, times(1)).readJpeg(root.resolve("beach.jpg"));
        verifyNoMoreInteractions(reader);
    }

    @Test
    void discoversAdditionalCameraAndRasterFormats() throws Exception {
        String[] extensions = {"heic", "heif", "hif", "cr2", "cr3", "nef", "nrw", "arw", "raf", "rw2",
                "orf", "pef", "srw", "3fr", "iiq", "dng", "jxl", "jp2", "psd", "tiff", "webp", "avif", "jfif"};
        for (String extension : extensions) Files.writeString(root.resolve("photo." + extension), "beach");
        var service = service();
        index(service);
        assertEquals(extensions.length, service.status().indexed());
        assertEquals(extensions.length, service.search("отдых у воды", 0, 60).total());
    }

    @Test
    void logsFileAndStageWhenConversionFailsAndContinuesWithOtherPhotos() throws Exception {
        Files.writeString(root.resolve("bad.heic"), "bad");
        Files.writeString(root.resolve("good.jpg"), "beach");
        when(reader.readJpeg(root.resolve("bad.heic"))).thenThrow(new IOException("missing HEIC decoder"));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PhotoSearchService.class);
        var capture = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        capture.start();
        logger.addAppender(capture);
        try {
            var service = service();
            service.start();
            await().atMost(Duration.ofSeconds(5)).until(() -> !service.status().running());
            assertEquals(1, service.status().failed());
            assertEquals(1, service.status().indexed());
            assertEquals(2, service.status().processed());
            assertTrue(service.status().error().contains("[convert]"));
            assertTrue(capture.list.stream().anyMatch(event -> event.getFormattedMessage().contains("PHOTO_FAILED path=bad.heic")
                    && event.getFormattedMessage().contains("stage=convert") && event.getThrowableProxy() != null));
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }

    @Test
    void searchesByMeaningRanksPaginatesAndEncodesUrls() throws Exception {
        Files.writeString(root.resolve("пляж #1.jpg"), "beach");
        Files.writeString(root.resolve("sea.jpg"), "sea");
        Files.writeString(root.resolve("mountain.jpg"), "mountain");
        var service = service();
        index(service);
        var page = service.search("отдых у воды", 0, 1);
        assertEquals(2, page.total());
        assertEquals("пляж #1.jpg", page.hits().getFirst().item().relativePath());
        assertEquals("Дети на пляже", page.hits().getFirst().description());
        assertTrue(page.hits().getFirst().item().previewUrl().contains("%23"));
        assertEquals("sea.jpg", service.search("отдых у воды", 1, 1).hits().getFirst().item().relativePath());
        assertTrue(service.search("совсем другое", 0, 10).hits().isEmpty());
    }

    @Test
    void resumesPersistedIndexAndUpdatesOnlyChangedFiles() throws Exception {
        Path photo = root.resolve("photo.jpg");
        Files.writeString(photo, "beach");
        var original = service();
        index(original);
        original.close();
        var restored = service();
        assertEquals(1, restored.status().available());
        index(restored);
        assertEquals(1, restored.status().unchanged());
        assertEquals(1, restored.status().processed());
        assertEquals(1, ai.descriptions);
        Files.writeString(photo, "mountain");
        assertTrue(restored.search("отдых у воды", 0, 10).hits().isEmpty());
        index(restored);
        assertEquals(1, restored.status().indexed());
        assertEquals(2, ai.descriptions);
        Files.delete(photo);
        assertTrue(restored.search("горы", 0, 10).hits().isEmpty());
        index(restored);
        assertEquals(0, restored.status().available());
        assertEquals(0, service().status().available());
    }

    @Test
    void changingModelsRebuildsDescriptionsAndVectors() throws Exception {
        Files.writeString(root.resolve("photo.jpg"), "beach");
        var service = service();
        index(service);
        ai.signature = "new-model";
        assertTrue(service.search("отдых у воды", 0, 10).hits().isEmpty());
        index(service);
        assertEquals(2, ai.descriptions);
        assertEquals(1, service.status().indexed());
    }

    @Test
    void stopsInFlightAnalysisAndAllowsAnotherRun() throws Exception {
        Files.writeString(root.resolve("photo.jpg"), "beach");
        var service = service();
        ai.entered = new CountDownLatch(1);
        ai.block = new CountDownLatch(1);
        service.start();
        assertTrue(ai.entered.await(5, TimeUnit.SECONDS));
        service.start(); // Idempotent while running.
        service.stop();
        await().atMost(Duration.ofSeconds(5)).until(() -> !service.status().running());
        assertEquals(0, service.status().available());
        assertEquals("Индексация остановлена", service.status().stage());
        ai.block = null;
        index(service);
        assertEquals(1, service.status().available());
    }

    @Test
    void providerFailureStopsRunAndIsVisibleInStatus() throws Exception {
        Files.writeString(root.resolve("a.jpg"), "beach");
        Files.writeString(root.resolve("b.jpg"), "beach");
        ai.unavailable = true;
        var service = service();
        service.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> !service.status().running());
        assertEquals(1, service.status().failed());
        assertEquals("Ошибка индексации", service.status().stage());
        assertNotNull(service.status().error());
        assertEquals(0, service.status().available());
    }

    @Test
    void rejectsBadRequestsAndInvalidVectors() throws Exception {
        var service = service();
        assertThrows(IllegalArgumentException.class, () -> service.search(" ", 0, 60));
        assertThrows(IllegalArgumentException.class, () -> service.search("фото", -1, 60));
        assertThrows(IllegalArgumentException.class, () -> service.search("фото", 0, 101));
        assertThrows(IOException.class, () -> PhotoSearchService.normalize(new float[]{Float.NaN}));
        assertThrows(IOException.class, () -> PhotoSearchService.normalize(new float[]{0, 0}));
        properties.setEnabled(false);
        assertThrows(IllegalStateException.class, service::start);
        assertThrows(IllegalStateException.class, () -> service.search("фото", 0, 60));
    }

    @Test
    void servesIndexedImageOnlyWhileOriginalAndVersionAreCurrent() throws Exception {
        Path photo = root.resolve("photo.jpg");
        Files.writeString(photo, "beach");
        var service = service();
        index(service);
        long version = Files.getLastModifiedTime(photo).toMillis();
        assertEquals("beach", Files.readString(service.image("photo.jpg", version)));
        assertThrows(IOException.class, () -> service.image("photo.jpg", version + 1));
        assertThrows(IOException.class, () -> service.image("../outside.jpg", version));
        assertThrows(IOException.class, () -> service.image("Bitcoin/photo.jpg", version));
        Files.writeString(photo, "mountain");
        assertThrows(IOException.class, () -> service.image("photo.jpg", version));
    }

    @Test
    void importsLegacyDescriptionsIntoSqliteWithoutCallingModels() throws Exception {
        Files.writeString(root.resolve("photo.jpg"), "beach");
        var legacy = service();
        index(legacy);
        assertEquals(1, ai.descriptions);
        legacy.close();
        app.getCache().setDatabasePath(temp.resolve("catalog.sqlite").toString());
        var database = new ru.homeserver.photoshare.homeserver.service.SqliteCacheStore(app);
        try {
            var imported = new PhotoSearchService(new FileService(app), app, properties, ai, reader, database);
            services.add(imported);
            assertEquals(1, imported.status().available());
            assertEquals(1, ai.descriptions);
            index(imported);
            assertEquals(1, imported.status().unchanged());
            assertEquals(1, ai.descriptions);
            var restored = new PhotoSearchService(new FileService(app), app, properties, ai, reader, database);
            services.add(restored);
            assertEquals(1, restored.search("отдых у воды", 0, 60).total());
            assertEquals(1, ai.descriptions);
        } finally { database.close(); }
    }

    @Test
    void retriesTemporaryFailureAndReusesDescriptionAfterRestart() throws Exception {
        Files.writeString(root.resolve("photo.jpg"), "beach");
        properties.setRetryDelaySeconds(1);
        var provider = mock(PhotoAiClient.class);
        when(provider.signature()).thenReturn("test-v1");
        when(provider.describe(any())).thenThrow(new PhotoAiClient.TemporaryException("temporary"))
                .thenReturn("description");
        when(provider.embed(anyString(), anyBoolean())).thenThrow(new PhotoAiClient.UnavailableException("offline"));
        var first = new PhotoSearchService(new FileService(app), app, properties, provider, reader);
        services.add(first);
        first.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> !first.status().running());
        assertEquals(0, first.status().available());
        verify(provider, times(2)).describe(any());
        doReturn(new float[]{1, 0}).when(provider).embed(anyString(), anyBoolean());
        var restored = new PhotoSearchService(new FileService(app), app, properties, provider, reader);
        services.add(restored);
        index(restored);
        assertEquals(1, restored.status().available());
        verify(provider, times(2)).describe(any());
    }

    @Test
    void failedRefreshRetainsCommittedEntry() throws Exception {
        Path photo = root.resolve("photo.jpg");
        Files.writeString(photo, "beach");
        var service = service();
        index(service);
        Files.writeString(photo, "changed image");
        ai.unavailable = true;
        service.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> !service.status().running());
        assertEquals(1, service.status().available());
        assertNotNull(service.status().error());
    }

    @Test
    void corruptedPreviewIsRebuiltAndManualStopDoesNotAutoResume() throws Exception {
        Files.writeString(root.resolve("photo.jpg"), "beach");
        var service = service();
        index(service);
        Path preview = service.image("photo.jpg", Files.getLastModifiedTime(root.resolve("photo.jpg")).toMillis());
        Files.writeString(preview, "corrupt");
        index(service);
        assertEquals(2, ai.descriptions);
        ai.entered = new CountDownLatch(1);
        ai.block = new CountDownLatch(1);
        Files.writeString(root.resolve("second.jpg"), "sea");
        service.start();
        assertTrue(ai.entered.await(5, TimeUnit.SECONDS));
        service.stop();
        await().atMost(Duration.ofSeconds(5)).until(() -> !service.status().running());
        var restored = service();
        restored.resumeInterruptedRun();
        assertFalse(restored.status().running());
    }

    @Test
    void resumesAfterServerShutdown() throws Exception {
        Files.writeString(root.resolve("photo.jpg"), "beach");
        var first = service();
        ai.entered = new CountDownLatch(1);
        ai.block = new CountDownLatch(1);
        first.start();
        assertTrue(ai.entered.await(5, TimeUnit.SECONDS));
        first.close();
        assertFalse(first.status().running());
        ai.block = null;
        var restored = service();
        restored.resumeInterruptedRun();
        await().atMost(Duration.ofSeconds(5)).until(() -> !restored.status().running());
        assertEquals(1, restored.status().available());
    }

    @Test
    void skipsDamagedSqliteRowAndRebuildsIt() throws Exception {
        Files.writeString(root.resolve("photo.jpg"), "beach");
        app.getCache().setDatabaseEnabled(true);
        Path dbPath = temp.resolve("catalog.sqlite");
        app.getCache().setDatabasePath(dbPath.toString());
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            var store = new ru.homeserver.photoshare.homeserver.service.SqliteCacheStore(app);
            try {
                var first = new PhotoSearchService(new FileService(app), app, properties, ai, reader, store);
                services.add(first);
                index(first);
                try (var sql = connection.createStatement()) { sql.executeUpdate("UPDATE photo_entries SET details_json='broken'"); }
                var restored = new PhotoSearchService(new FileService(app), app, properties, ai, reader, store);
                services.add(restored);
                assertEquals(0, restored.status().available());
                index(restored);
                assertEquals(1, restored.status().available());
            } finally { store.close(); }
        }
    }

    @Test
    void videoIndexIsSeparatePersistentAndReturnsPlayableResults() throws Exception {
        Files.writeString(root.resolve("photo.jpg"), "beach");
        Files.writeString(root.resolve("clip.mp4"), "beach");
        var photos = service();
        index(photos);
        var videos = new PhotoSearchService(new FileService(app), app, properties, ai, reader, null, true);
        services.add(videos);
        index(videos);
        assertEquals(1, videos.status().available());
        var hit = videos.search("отдых у воды", 0, 60).hits().getFirst();
        assertEquals("clip.mp4", hit.item().relativePath());
        assertEquals("video", hit.item().type());
        assertEquals("/api/files/video-thumbnail?path=clip.mp4", hit.item().thumbnailUrl());
        assertTrue(Files.isRegularFile(videos.image("clip.mp4", hit.item().lastModified())));
        var restored = new PhotoSearchService(new FileService(app), app, properties, ai, reader, null, true);
        services.add(restored);
        assertEquals(1, restored.status().available());
        assertEquals("photo.jpg", photos.search("отдых у воды", 0, 60).hits().getFirst().item().relativePath());
    }

    @Test
    void filtersAndSortsAcrossAllMatchesBeforePagination() throws Exception {
        Files.writeString(root.resolve("a.jpg"), "beach");
        Files.writeString(root.resolve("b.jpg"), "sea");
        Files.writeString(root.resolve("c.jpg"), "beach");
        var service = service();
        index(service);
        var page = service.search("отдых у воды", 1, 1,
                item -> !item.name().equals("a.jpg"),
                java.util.Comparator.comparing(ru.homeserver.photoshare.homeserver.dto.FileItemDto::name).reversed());
        assertEquals(2, page.total());
        assertEquals("b.jpg", page.hits().getFirst().item().name());
    }

    @Test
    void skipsGenerationFailureAndContinuesWithNextFile() throws Exception {
        Files.writeString(root.resolve("one.mp4"), "beach");
        Files.writeString(root.resolve("two.mp4"), "beach");
        var provider = mock(PhotoAiClient.class);
        when(provider.signature()).thenReturn("generation-failure-test");
        when(provider.describe(any())).thenThrow(new PhotoAiClient.GenerationException("token repeat limit reached"))
                .thenReturn("Дети на пляже");
        when(provider.embed(anyString(), anyBoolean())).thenReturn(new float[]{1, 0});
        var service = new PhotoSearchService(new FileService(app), app, properties, provider, reader, null, true);
        services.add(service);
        service.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> !service.status().running());
        assertEquals(2, service.status().scanned());
        assertEquals(2, service.status().processed());
        assertEquals(1, service.status().failed());
        assertEquals(1, service.status().indexed());
        assertEquals("Индексация завершена с ошибками", service.status().stage());
        verify(provider, times(2)).describe(any());
    }

    @Test
    void intentionalShortVideoSkipIsProcessedWithoutFailureOrAiCall() throws Exception {
        Files.writeString(root.resolve("short.mp4"), "short");
        doThrow(new MediaSkippedException("video-duration-below-2-seconds")).when(reader).readJpeg(any());
        var service = new PhotoSearchService(new FileService(app), app, properties, ai, reader, null, true);
        services.add(service);
        index(service);
        assertEquals(1, service.status().processed());
        assertEquals(0, service.status().failed());
        assertEquals(0, service.status().indexed());
        assertEquals(0, ai.descriptions);
    }

    private static class FakeAi implements PhotoAiClient {
        volatile String signature = "test-v1";
        volatile int descriptions;
        volatile CountDownLatch entered, block;
        volatile boolean unavailable;

        public String describe(byte[] jpeg) throws IOException, InterruptedException {
            if (unavailable) throw new UnavailableException("Ollama недоступна");
            descriptions++;
            if (entered != null) entered.countDown();
            if (block != null) block.await();
            return switch (new String(jpeg, java.nio.charset.StandardCharsets.UTF_8)) {
                case "beach" -> "Дети на пляже";
                case "sea" -> "Море и парусник";
                default -> "Снежные горы";
            };
        }

        public float[] embed(String text, boolean query) {
            return switch (text) {
                case "Дети на пляже", "отдых у воды" -> new float[]{1, 0, 0};
                case "Море и парусник" -> new float[]{0.8f, 0.6f, 0};
                case "Снежные горы", "горы" -> new float[]{0, 1, 0};
                default -> new float[]{0, 0, 1};
            };
        }

        public String signature() { return signature; }
    }
}
