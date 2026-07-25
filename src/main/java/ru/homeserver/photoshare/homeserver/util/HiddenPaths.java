package ru.homeserver.photoshare.homeserver.util;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

public final class HiddenPaths {

    private static final Set<String> HIDDEN_NAMES = Set.of(
            ".metadata_cache",
            ".thumbnails",
            ".previews",
            ".preview_journal",
            ".folder_cache",
            ".upload_tmp",
            ".security",
            ".logs",
            "$recycle.bin",
            "system volume information"
    );

    private HiddenPaths() {
    }

    public static boolean isHiddenName(String name) {
        if (name == null) {
            return false;
        }

        return HIDDEN_NAMES.contains(
                name.toLowerCase(Locale.ROOT)
        );
    }

    public static boolean shouldSkip(Path path) {
        if (path == null) {
            return false;
        }

        Path fileName = path.getFileName();

        if (fileName != null && isHiddenName(fileName.toString())) {
            return true;
        }

        String full = path.toAbsolutePath()
                .normalize()
                .toString()
                .replace("\\", "/")
                .toLowerCase(Locale.ROOT);

        for (String hiddenName : HIDDEN_NAMES) {
            if (full.contains("/" + hiddenName + "/")) {
                return true;
            }
        }

        return false;
    }
}
