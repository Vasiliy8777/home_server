package ru.homeserver.photoshare.homeserver.video;

public record HlsProgressState(
        int progress,
        double convertedSeconds,
        double totalSeconds,
        boolean running,
        boolean ready
) {}
