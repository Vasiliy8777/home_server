package ru.homeserver.photoshare.homeserver.share;

public record ShareCreateRequest(
        String path,
        SharePermission permission,
        Integer expiresInDays
) {
}
