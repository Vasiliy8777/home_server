package ru.homeserver.photoshare.homeserver.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.homeserver.photoshare.homeserver.config.AppProperties;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PhotoImageReaderTest {
    @TempDir Path temp;

    @Test
    void convertsAndResizesPhotoWithoutChangingOriginalWhenMagickIsUnavailable() throws Exception {
        var properties = new AppProperties();
        properties.setMagickPath(temp.resolve("missing-magick").toString());
        Path source = temp.resolve("photo.png");
        ImageIO.write(new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_ARGB), "png", source.toFile());
        byte[] original = Files.readAllBytes(source);
        byte[] jpeg = new PhotoImageReader(properties).readJpeg(source);
        BufferedImage converted = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertEquals(1280, converted.getWidth());
        assertEquals(640, converted.getHeight());
        assertArrayEquals(original, Files.readAllBytes(source));
    }
}
