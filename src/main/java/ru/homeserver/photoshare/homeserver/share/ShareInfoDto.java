package ru.homeserver.photoshare.homeserver.share;

import java.time.Instant;

public record ShareInfoDto(
        String token,
        String path,
        boolean directory,
        SharePermission permission,
        Instant createdAt,
        Instant expiresAt,
        boolean active,
        String url
) {
}
