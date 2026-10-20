package ru.homeserver.photoshare.homeserver.util;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Raster images accepted by photo search; actual decoding depends on installed codecs. */
public final class PhotoFormats {
    private static final Set<String> RAW = Set.of("raw", "dng", "cr2", "cr3", "crw", "nef", "nrw",
            "arw", "srf", "sr2", "raf", "rw2", "rwl", "orf", "pef", "srw", "3fr", "fff", "iiq",
            "dcr", "k25", "kdc", "erf", "mef", "mos", "mrw", "mdc", "rmf", "sti", "x3f");
    private static final Set<String> RASTER = Set.of("jpg", "jpeg", "jpe", "jfif", "pjpeg", "png", "apng",
            "gif", "webp", "bmp", "dib", "tif", "tiff", "avif", "heic", "heif", "hif", "jxl",
            "jp2", "j2k", "jpc", "j2c", "jpm", "jpt", "psd", "psb", "tga", "pcx",
            "pbm", "pgm", "ppm", "pnm", "pam", "ico", "cur");

    private PhotoFormats() {}

    public static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "(none)" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isPhoto(Path path) {
        String ext = extension(path);
        return RASTER.contains(ext) || RAW.contains(ext);
    }

    public static boolean isRaw(Path path) { return RAW.contains(extension(path)); }
}
