package ru.homeserver.photoshare.homeserver.util;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ua_parser.Client;
import ua_parser.Parser;

import java.io.IOException;

@Service
public class CloudAccessLogService {
    private static final Logger log = LoggerFactory.getLogger("CLOUD_ACCESS");

    private final Parser userAgentParser;

    public CloudAccessLogService() throws IOException {
        this.userAgentParser = new Parser();
    }

    public void event(String event, HttpServletRequest request, String details) {
        String userAgent = request.getHeader("User-Agent");
        Client client = parseUserAgent(userAgent);

        log.info(
                "event={} ip={} method={} uri={} query=\"{}\" referer=\"{}\" range=\"{}\" device=\"{}\" os=\"{}\" browser=\"{}\" details=\"{}\" userAgent=\"{}\"",
                event,
                getClientIp(request),
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                request.getHeader("Referer"),
                request.getHeader("Range"),
                device(client),
                os(client),
                browser(client),
                details,
                userAgent
        );
    }

    public void error(String event, HttpServletRequest request, String details, Exception e) {
        String userAgent = request.getHeader("User-Agent");
        Client client = parseUserAgent(userAgent);

        log.warn(
                "event={} ip={} method={} uri={} query=\"{}\" referer=\"{}\" range=\"{}\" device=\"{}\" os=\"{}\" browser=\"{}\" details=\"{}\" error=\"{}\" userAgent=\"{}\"",
                event,
                getClientIp(request),
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                request.getHeader("Referer"),
                request.getHeader("Range"),
                device(client),
                os(client),
                browser(client),
                details,
                e.toString(),
                userAgent
        );
    }

    private Client parseUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }

        return userAgentParser.parse(userAgent);
    }

    private String browser(Client client) {
        if (client == null || client.userAgent == null) {
            return "Unknown";
        }

        return client.userAgent.family + " " +
                nullToEmpty(client.userAgent.major);
    }

    private String os(Client client) {
        if (client == null || client.os == null) {
            return "Unknown";
        }

        return client.os.family + " " +
                nullToEmpty(client.os.major);
    }

    private String device(Client client) {
        if (client == null || client.device == null) {
            return "Unknown";
        }

        return client.device.family;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");

        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }

        String realIp = request.getHeader("X-Real-IP");

        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        return request.getRemoteAddr();
    }
}
