package ru.homeserver.photoshare.homeserver.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import ru.homeserver.photoshare.homeserver.config.AppProperties;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileServiceTest {
    @TempDir Path root;
    private FileService service;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties properties = new AppProperties();
        properties.setStorageRoot(root.toString());
        service = new FileService(properties);
    }

    @Test
    void resolvesNormalizedRelativePaths() {
        assertEquals(root.resolve("photos/image.jpg"), service.resolveSafe("photos/./image.jpg"));
        assertEquals("photos/image.jpg", service.toRelative(root.resolve("photos/image.jpg")));
        assertEquals(root, service.resolveSafe(null));
    }

    @Test
    void rejectsTraversalAndNulCharacters() {
        assertThrows(IllegalArgumentException.class, () -> service.resolveSafe("../outside.jpg"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveSafe("..\\outside.jpg"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveSafe("photos/../../outside"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveSafe("bad\0name"));
    }

    @Test
    void createsUploadsRenamesAndDeletesFiles() throws Exception {
        service.createFolder("", "photos");
        service.upload("photos", new MultipartFile[]{
                new MockMultipartFile("file", "image.jpg", "image/jpeg", new byte[]{1, 2, 3})
        });
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(root.resolve("photos/image.jpg")));
        service.rename("photos/image.jpg", "renamed.jpg");
        assertFalse(Files.exists(root.resolve("photos/image.jpg")));
        assertTrue(Files.exists(root.resolve("photos/renamed.jpg")));
        service.delete("photos");
        assertFalse(Files.exists(root.resolve("photos")));
    }

    @Test
    void listsDirectoriesFirstAndAppliesPagination() throws Exception {
        Files.writeString(root.resolve("a.txt"), "a");
        Files.writeString(root.resolve("b.txt"), "b");
        Files.createDirectory(root.resolve("z-folder"));
        var page = service.list("", 0, 2);
        assertEquals(2, page.size());
        assertTrue(page.getFirst().directory());
        assertEquals("a.txt", page.get(1).name());
        assertEquals("b.txt", service.list("", 2, 2).getFirst().name());
    }

    @Test
    void excludesInternalDirectoriesFromListingAndCounts() throws Exception {
        Files.createDirectory(root.resolve(".security"));
        Files.createDirectory(root.resolve(".thumbnails"));
        Files.writeString(root.resolve("visible.txt"), "data");
        assertEquals(1, service.countItems(""));
        assertEquals("visible.txt", service.list("", 0, 100).getFirst().name());
    }

    @Test
    void rejectsInvalidFolderAndRenameNames() {
        assertThrows(IllegalArgumentException.class, () -> service.createFolder("", "../outside"));
        assertThrows(IllegalArgumentException.class, () -> service.rename("file", "../outside"));
    }

    @Test
    void ignoresEmptyUploadAndMissingDelete() throws Exception {
        service.upload("", new MultipartFile[]{new MockMultipartFile("file", new byte[0])});
        assertTrue(service.list("", 0, 100).isEmpty());
        assertDoesNotThrow(() -> service.delete("missing"));
    }
}
