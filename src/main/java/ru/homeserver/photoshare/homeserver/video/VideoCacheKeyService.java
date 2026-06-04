package ru.homeserver.photoshare.homeserver.video;


import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;

@Service
public class VideoCacheKeyService {

    public String cacheKey(Path file) {
        try {
            String source = file.toAbsolutePath().normalize().toString();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(source.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create video cache key", e);
        }
    }
}