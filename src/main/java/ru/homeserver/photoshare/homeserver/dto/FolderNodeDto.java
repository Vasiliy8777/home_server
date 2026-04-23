package ru.homeserver.photoshare.homeserver.dto;

import java.util.List;

public record FolderNodeDto(
        String name,
        String relativePath,
        List<FolderNodeDto> children
) {
}
