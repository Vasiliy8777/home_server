package ru.homeserver.photoshare.homeserver.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.multipart.MultipartFile;
import ru.homeserver.photoshare.homeserver.dto.UploadSessionDto;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.*;

/** Shared lock stripes cover private/public upload routes without retaining session ids. */
public final class UploadChunks {
    private static final Object[] LOCKS = java.util.stream.IntStream.range(0, 128).mapToObj(i -> new Object()).toArray();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private UploadChunks() {}
    public static Object lock(String id) { return LOCKS[(id.hashCode() & Integer.MAX_VALUE) % LOCKS.length]; }

    public static boolean valid(UploadSessionDto meta, int index, long length) {
        if (index < 0 || index >= meta.getTotalChunks() || meta.getChunkSize() <= 0) return false;
        long offset = (long) index * meta.getChunkSize();
        long expected = Math.min(meta.getChunkSize(), meta.getFileSize() - offset);
        return expected > 0 && length == expected;
    }

    public static void write(Path temp, UploadSessionDto meta, int index, MultipartFile part) throws IOException {
        if (!valid(meta, index, part.getSize())) throw new IOException("Invalid chunk size/index");
        try (var input = part.getInputStream(); var output = new RandomAccessFile(temp.toFile(), "rw")) {
            output.seek((long) index * meta.getChunkSize());
            byte[] buffer = new byte[64 * 1024];
            long remaining = part.getSize();
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new IOException("Incomplete chunk");
                if (count == 0) continue;
                output.write(buffer, 0, count);
                remaining -= count;
            }
            if (input.read() != -1) throw new IOException("Chunk exceeds declared size");
        }
    }

    public static void writeMetadata(Path path, UploadSessionDto meta) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), "upload-meta-", ".tmp");
        try {
            MAPPER.writeValue(temp.toFile(), meta);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
