package ru.homeserver.photoshare.homeserver.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import ru.homeserver.photoshare.homeserver.util.PhotoFormats;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit read-only verification of selected real files with installed ImageMagick. */
@EnabledIfEnvironmentVariable(named = "PHOTO_SEARCH_FORMAT_TEST", matches = "true")
class PhotoFormatsLiveTest {
    @Test
    void convertsSelectedFilesAndOptionallyVerifiesRawDescription() throws Exception {
        String[] samples = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(System.getenv("PHOTO_SEARCH_FORMAT_SAMPLES"), String[].class);
        assertTrue(samples.length > 0);
        var app = new AppProperties();
        app.setMagickPath("magick");
        var reader = new PhotoImageReader(app);
        boolean verifyModel = "true".equals(System.getenv("PHOTO_SEARCH_FORMAT_MODEL_TEST"));
        var ai = new OllamaPhotoAiClient(new PhotoSearchProperties());
        for (String sample : samples) {
            Path photo = Path.of(sample);
            long size = Files.size(photo);
            var modified = Files.getLastModifiedTime(photo);
            assertTrue(PhotoFormats.isPhoto(photo));
            byte[] jpeg = reader.readJpeg(photo);
            var decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
            assertNotNull(decoded);
            assertTrue(decoded.getWidth() <= 1280 && decoded.getHeight() <= 1280);
            System.out.printf("FORMAT VERIFIED: extension=%s dimensions=%dx%d jpegBytes=%d%n",
                    PhotoFormats.extension(photo), decoded.getWidth(), decoded.getHeight(), jpeg.length);
            if (verifyModel && PhotoFormats.isRaw(photo)) {
                String caption = ai.describe(jpeg);
                assertFalse(caption.isBlank());
                assertTrue(caption.matches("(?s).*[А-Яа-яЁё].*"));
                System.out.printf("RAW VISION VERIFIED: extension=%s descriptionChars=%d%n",
                        PhotoFormats.extension(photo), caption.length());
            }
            assertEquals(size, Files.size(photo));
            assertEquals(modified, Files.getLastModifiedTime(photo));
        }
    }
}
