package ru.homeserver.photoshare.homeserver.search;

import java.io.IOException;

public interface PhotoAiClient {
    String describe(byte[] jpeg) throws IOException, InterruptedException;
    float[] embed(String text, boolean query) throws IOException, InterruptedException;
    String signature();

    class TemporaryException extends UnavailableException {
        public TemporaryException(String message, Throwable cause) { super(message, cause); }
        public TemporaryException(String message) { super(message); }
    }

    class UnavailableException extends IOException {
        public UnavailableException(String message, Throwable cause) { super(message, cause); }
        public UnavailableException(String message) { super(message); }
    }
}
