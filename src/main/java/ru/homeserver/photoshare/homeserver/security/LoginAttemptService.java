package ru.homeserver.photoshare.homeserver.security;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_TIME_SECONDS = 15 * 60;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String key) {
        Attempt attempt = attempts.get(key);

        if (attempt == null) {
            return false;
        }

        if (attempt.blockedUntil != null && Instant.now().isBefore(attempt.blockedUntil)) {
            return true;
        }

        if (attempt.blockedUntil != null && Instant.now().isAfter(attempt.blockedUntil)) {
            attempts.remove(key);
        }

        return false;
    }

    public void loginFailed(String key) {
        Attempt attempt = attempts.computeIfAbsent(key, k -> new Attempt());

        attempt.count++;

        if (attempt.count >= MAX_ATTEMPTS) {
            attempt.blockedUntil = Instant.now().plusSeconds(LOCK_TIME_SECONDS);
        }
    }

    public void loginSucceeded(String key) {
        attempts.remove(key);
    }

    private static class Attempt {
        int count = 0;
        Instant blockedUntil;
    }
}