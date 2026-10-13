package ru.homeserver.photoshare.homeserver.share;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import ru.homeserver.photoshare.homeserver.service.FileService;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ShareServiceTest {
    @TempDir Path root;
    private ShareService service;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties properties = new AppProperties();
        properties.setStorageRoot(root.toString());
        Files.createDirectory(root.resolve("photos"));
        Files.writeString(root.resolve("photos/image.jpg"), "photo");
        service = new ShareService(new FileService(properties));
    }

    @Test
    void persistsLinksAndReusesSamePathAndPermission() throws Exception {
        var request = new ShareCreateRequest("photos", SharePermission.DOWNLOAD, 1);
        var link = service.create(request, "https://cloud.example");
        assertEquals(link.token(), service.create(request, "https://cloud.example").token());
        assertEquals(SharePermission.DOWNLOAD, service.requireActive(link.token()).getPermission());
        assertTrue(link.url().contains(link.token()));
        assertNotNull(link.expiresAt());
        assertEquals(1, service.list("https://cloud.example").size());
    }

    @Test
    void disabledAndDeletedLinksCannotBeUsed() throws Exception {
        var link = service.create(new ShareCreateRequest("photos", null, null), "https://cloud.example");
        assertEquals(SharePermission.VIEW, link.permission());
        service.update(link.token(), new ShareUpdateRequest(null, false), "https://cloud.example");
        assertFalse(service.isTokenActive(link.token()));
        assertThrows(IllegalArgumentException.class, () -> service.requireActive(link.token()));
        service.deleteLink(link.token());
        assertTrue(service.list("https://cloud.example").isEmpty());
    }

    @Test
    void confinesPathsToSharedDirectory() throws Exception {
        var dto = service.create(new ShareCreateRequest("photos", SharePermission.VIEW, null), "https://cloud.example");
        var link = service.requireActive(dto.token());
        assertEquals("photos/image.jpg", service.resolveInsideShare(link, "image.jpg"));
        assertEquals("photos", service.resolveInsideShare(link, ""));
        assertThrows(IllegalArgumentException.class, () -> service.resolveInsideShare(link, "../private.txt"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveInsideShare(link, "..\\private.txt"));
    }

    @Test
    void fileLinksAllowOnlyTheirFileAndCannotGrantUpload() throws Exception {
        var dto = service.create(new ShareCreateRequest("photos/image.jpg", SharePermission.DOWNLOAD, null), "https://cloud.example");
        var link = service.requireActive(dto.token());
        assertEquals("photos/image.jpg", service.resolveInsideShare(link, ""));
        assertEquals("photos/image.jpg", service.resolveInsideShare(link, "image.jpg"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveInsideShare(link, "other.jpg"));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ShareCreateRequest("photos/image.jpg", SharePermission.UPLOAD, null), "https://cloud.example"));
    }

    @Test
    void permissionsMatchViewDownloadUploadAndManageLevels() {
        assertFalse(SharePermission.VIEW.canDownload());
        assertFalse(SharePermission.DOWNLOAD.canUpload());
        assertTrue(SharePermission.DOWNLOAD.canDownload());
        assertTrue(SharePermission.UPLOAD.canDownload());
        assertTrue(SharePermission.UPLOAD.canUpload());
        assertFalse(SharePermission.UPLOAD.canDelete());
        assertTrue(SharePermission.MANAGE.canDelete());
    }
}
