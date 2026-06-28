package ru.homeserver.photoshare.homeserver.share;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import ru.homeserver.photoshare.homeserver.dto.FileItemDto;
import ru.homeserver.photoshare.homeserver.service.FileService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Service
public class ShareService {

    private final FileService fileService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final SecureRandom secureRandom = new SecureRandom();

    private final Path shareDir;

    public ShareService(FileService fileService) throws IOException {
        this.fileService = fileService;
        this.shareDir = fileService.getRootPath()
                .resolve(".security")
                .resolve("share-links")
                .normalize();

        Files.createDirectories(shareDir);
    }

    public boolean isTokenActive(String token) {
        try {
            requireActive(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public ShareInfoDto create(ShareCreateRequest request, String baseUrl) throws IOException {
        Path target = fileService.resolveSafe(request.path());

        if (!Files.exists(target)) {
            throw new IllegalArgumentException("Файл или папка не найдены");
        }
        SharePermission permission =
                request.permission() == null ? SharePermission.VIEW : request.permission();

        String relativePath = fileService.toRelative(target);

        ShareLink existing = findExisting(relativePath, permission);

        if (existing != null) {
            return toDto(existing, baseUrl);
        }
        String token = generateToken();

        ShareLink link = new ShareLink();
        link.setToken(token);
        link.setPath(fileService.toRelative(target));
        link.setDirectory(Files.isDirectory(target));
        link.setPermission(request.permission() == null ? SharePermission.VIEW : request.permission());
        link.setCreatedAt(Instant.now());
        link.setActive(true);

        if (request.expiresInDays() != null && request.expiresInDays() > 0) {
            link.setExpiresAt(Instant.now().plusSeconds(request.expiresInDays() * 24L * 60L * 60L));
        }

        save(link);

        return toDto(link, baseUrl);
    }

    public List<ShareInfoDto> list(String baseUrl) throws IOException {
        if (!Files.exists(shareDir)) {
            return List.of();
        }

        try (var stream = Files.list(shareDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> {
                        try {
                            return objectMapper.readValue(path.toFile(), ShareLink.class);
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(link -> link != null)
                    .map(link -> toDto(link, baseUrl))
                    .toList();
        }
    }

    private ShareLink findExisting(String path, SharePermission permission) throws IOException {
        if (!Files.exists(shareDir)) {
            return null;
        }

        try (var stream = Files.list(shareDir)) {
            return stream
                    .filter(file -> file.getFileName().toString().endsWith(".json"))
                    .map(file -> {
                        try {
                            return objectMapper.readValue(file.toFile(), ShareLink.class);
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(link -> link != null)
                    .filter(link -> path.equals(link.getPath()))
                    .filter(link -> permission == link.getPermission())
                    .findFirst()
                    .orElse(null);
        }
    }

    public ShareInfoDto update(String token, ShareUpdateRequest request, String baseUrl) throws IOException {
        ShareLink link = requireLink(token);

        if (request.permission() != null) {
            link.setPermission(request.permission());
        }

        if (request.active() != null) {
            link.setActive(request.active());
        }

        save(link);

        return toDto(link, baseUrl);
    }

    public void deleteLink(String token) throws IOException {
        Files.deleteIfExists(linkFile(token));
    }

    public ShareLink requireActive(String token) throws IOException {
        ShareLink link = requireLink(token);

        if (!link.isActive()) {
            throw new IllegalArgumentException("Ссылка отключена");
        }

        if (link.getExpiresAt() != null && Instant.now().isAfter(link.getExpiresAt())) {
            throw new IllegalArgumentException("Срок действия ссылки истёк");
        }

        return link;
    }

    public String resolveInsideShare(ShareLink link, String publicRelativePath) {
        Path sharedRoot = fileService.resolveSafe(link.getPath());

        String clean = publicRelativePath == null ? "" : publicRelativePath.trim();
        clean = clean.replace("\\", "/");

        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }

        if (clean.contains("\0")) {
            throw new IllegalArgumentException("Invalid path");
        }

        Path resolved = clean.isBlank()
                ? sharedRoot
                : sharedRoot.resolve(clean).normalize();

        if (!resolved.startsWith(sharedRoot)) {
            throw new IllegalArgumentException("Access outside shared folder is forbidden");
        }

        return fileService.toRelative(resolved);
    }

    public FileItemDto toPublicItem(ShareLink link, FileItemDto item) {
        Path sharedRoot = fileService.resolveSafe(link.getPath());
        Path itemPath = fileService.resolveSafe(item.relativePath());

        String publicRelative = sharedRoot.equals(itemPath)
                ? ""
                : sharedRoot.relativize(itemPath).toString().replace("\\", "/");

        String token = link.getToken();
        String encoded = encode(publicRelative);

        String previewUrl = null;
        String thumbnailUrl = null;
        String downloadUrl = null;
        String hlsPrepareUrl = null;
        String hlsStatusUrl = null;

        if (!item.directory()) {
            thumbnailUrl = "/share/" + token + "/thumbnail?path=" + encoded;

            if ("image".equals(item.type())) {
                previewUrl = "/share/" + token + "/raw?path=" + encoded;
            }

            if ("video".equals(item.type())) {
                previewUrl = "/share/" + token + "/stream?path=" + encoded;
            }

            if (link.getPermission().canDownload()) {
                downloadUrl = "/share/" + token + "/download?path=" + encoded;
            }

            if (Boolean.TRUE.equals(item.hlsSupported())) {
                hlsPrepareUrl = "/share/" + token + "/video/hls/prepare?path=" + encoded;
                hlsStatusUrl = "/share/" + token + "/video/hls/status?path=" + encoded;
            }
        }

        return new FileItemDto(
                item.name(),
                publicRelative,
                item.directory(),
                item.size(),
                item.type(),
                previewUrl,
                thumbnailUrl,
                downloadUrl,
                item.lastModified(),
                item.createdAt(),
                item.fileCount(),
                item.folderCount(),
                item.hlsSupported(),
                hlsPrepareUrl,
                hlsStatusUrl
        );
    }

    private ShareLink requireLink(String token) throws IOException {
        Path file = linkFile(token);

        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Ссылка не найдена");
        }

        return objectMapper.readValue(file.toFile(), ShareLink.class);
    }

    private void save(ShareLink link) throws IOException {
        objectMapper.writeValue(linkFile(link.getToken()).toFile(), link);
    }

    private Path linkFile(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{20,120}")) {
            throw new IllegalArgumentException("Invalid token");
        }

        return shareDir.resolve(token + ".json").normalize();
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    private ShareInfoDto toDto(ShareLink link, String baseUrl) {
        return new ShareInfoDto(
                link.getToken(),
                link.getPath(),
                link.isDirectory(),
                link.getPermission(),
                link.getCreatedAt(),
                link.getExpiresAt(),
                link.isActive(),
                baseUrl + "/share/" + link.getToken()
        );
    }

    private String encode(String value) {
        return value == null ? "" : value.replace(" ", "%20");
    }
}
