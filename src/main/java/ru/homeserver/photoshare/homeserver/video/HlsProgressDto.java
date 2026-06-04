package ru.homeserver.photoshare.homeserver.video;

public record HlsProgressDto(
        HlsStatus status,
        int progress
) {}
