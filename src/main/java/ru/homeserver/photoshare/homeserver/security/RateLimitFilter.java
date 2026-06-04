package ru.homeserver.photoshare.homeserver.security;

import io.github.bucket4j.Bucket;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitFilter implements Filter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String uri = httpRequest.getRequestURI();

        if (
                uri.startsWith("/api/files/upload-chunk")
                        || uri.startsWith("/api/files/upload/init")
                        || uri.startsWith("/api/files/upload/complete")
                        || uri.startsWith("/api/files/prepared-items")
                        || uri.startsWith("/api/files/metadata")
                        || uri.startsWith("/api/files/image-thumbnail")
                        || uri.startsWith("/api/files/video-thumbnail")
                        || uri.startsWith("/api/files/stream")
                        || uri.startsWith("/api/video/hls")
        ) {
            chain.doFilter(request, response);
            return;
        }

        String key = httpRequest.getRemoteAddr();

        Bucket bucket = buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(limit -> limit
                        .capacity(300)
                        .refillGreedy(300, Duration.ofMinutes(1))
                )
                .build());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            httpResponse.setStatus(429);
            httpResponse.setContentType("text/plain;charset=UTF-8");
            httpResponse.getWriter().write("Too many requests");
        }
    }
}
