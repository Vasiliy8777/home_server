package ru.homeserver.photoshare.homeserver.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiddenPathsTest {
    @Test
    void skipsExcludedDirectoriesAndTheirContentsRegardlessOfCase() {
        for (String name : new String[]{".logs", "$recycle.bin", "system volume information",
                "Bitcoin", "BitcoinCoreSpeedCheck", "BitcoinJavaNode"}) {
            for (String variant : new String[]{name, name.toUpperCase(Locale.ROOT),
                    name.toLowerCase(Locale.ROOT)}) {
                assertTrue(HiddenPaths.isHiddenName(variant), variant);
                assertTrue(HiddenPaths.shouldSkip(Path.of("storage", variant)), variant);
                assertTrue(HiddenPaths.shouldSkip(Path.of("storage", variant, "nested", "photo.jpg")), variant);
            }
        }
    }

    @Test
    void keepsOrdinaryPathsAndNamesContainingOnlyPartOfAnExcludedName() {
        assertFalse(HiddenPaths.shouldSkip(Path.of("storage", "photos", "photo.jpg")));
        assertFalse(HiddenPaths.shouldSkip(Path.of("storage", "Bitcoin-backup", "photo.jpg")));
        assertFalse(HiddenPaths.shouldSkip(Path.of("storage", "my.logs", "photo.jpg")));
        assertFalse(HiddenPaths.shouldSkip(null));
        assertFalse(HiddenPaths.isHiddenName(null));
    }
}
