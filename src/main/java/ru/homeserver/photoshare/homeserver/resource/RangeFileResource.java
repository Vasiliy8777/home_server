package ru.homeserver.photoshare.homeserver.resource;

import org.springframework.core.io.AbstractResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/**
 * Resource для отдачи только части файла.
 *
 * Обычный FileSystemResource отдает весь файл.
 * Этот класс позволяет начать чтение с конкретного байта
 * и ограничить длину отдаваемого диапазона.
 */
public class RangeFileResource extends AbstractResource {

    private final Path file;
    private final long start;
    private final long length;

    public RangeFileResource(Path file, long start, long length) {
        this.file = file;
        this.start = start;
        this.length = length;
    }

    @Override
    public String getDescription() {
        return "Range resource [" + file + ", start=" + start + ", length=" + length + "]";
    }

    @Override
    public String getFilename() {
        return file.getFileName().toString();
    }

    @Override
    public long contentLength() {
        return length;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
        raf.seek(start);

        return new InputStream() {
            private long remaining = length;

            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    return -1;
                }

                int data = raf.read();
                if (data != -1) {
                    remaining--;
                }
                return data;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (remaining <= 0) {
                    return -1;
                }

                int bytesToRead = (int) Math.min(len, remaining);
                int bytesRead = raf.read(b, off, bytesToRead);

                if (bytesRead > 0) {
                    remaining -= bytesRead;
                }

                return bytesRead;
            }

            @Override
            public void close() throws IOException {
                raf.close();
            }
        };
    }
}