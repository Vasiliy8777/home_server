package ru.homeserver.photoshare.homeserver.video;

public record HlsConversionState(
        HlsStatus status,
        String playlistUrl,
        String message
) {
}