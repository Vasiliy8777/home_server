package ru.homeserver.photoshare.homeserver.share;

public record ShareUpdateRequest(
        SharePermission permission,
        Boolean active
) {
}
