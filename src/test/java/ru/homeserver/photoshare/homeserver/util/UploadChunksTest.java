package ru.homeserver.photoshare.homeserver.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import ru.homeserver.photoshare.homeserver.dto.UploadSessionDto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UploadChunksTest {
    @TempDir Path temp;
    private UploadSessionDto session() {
        var meta = new UploadSessionDto();
        meta.setUploadId("test"); meta.setChunkSize(3); meta.setFileSize(5); meta.setTotalChunks(2);
        return meta;
    }

    @Test
    void writesOutOfOrderChunksWithoutMaterializingWholePart() throws Exception {
        var meta = session();
        Path file = temp.resolve("upload.tmp");
        var second = new MockMultipartFile("file", new byte[]{4, 5}) {
            @Override public byte[] getBytes() { throw new AssertionError("Must stream upload part"); }
        };
        UploadChunks.write(file, meta, 1, second);
        UploadChunks.write(file, meta, 0, new MockMultipartFile("file", new byte[]{1, 2, 3}));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, Files.readAllBytes(file));
        assertFalse(UploadChunks.valid(meta, 1, 3));
        assertThrows(IOException.class, () -> UploadChunks.write(file, meta, 1, new MockMultipartFile("file", new byte[]{1})));
    }

    @Test
    void metadataReplacementIsReadableAndLeavesNoTemporaryFiles() throws Exception {
        Path file = temp.resolve("test.meta.json");
        var meta = session();
        UploadChunks.writeMetadata(file, meta);
        meta.getUploadedChunks().add(1);
        UploadChunks.writeMetadata(file, meta);
        var stored = new com.fasterxml.jackson.databind.ObjectMapper().readValue(file.toFile(), UploadSessionDto.class);
        assertEquals(java.util.List.of(1), stored.getUploadedChunks());
        try (var files = Files.list(temp)) { assertEquals(1, files.count()); }
        assertSame(UploadChunks.lock("test"), UploadChunks.lock("test"));
    }
}
