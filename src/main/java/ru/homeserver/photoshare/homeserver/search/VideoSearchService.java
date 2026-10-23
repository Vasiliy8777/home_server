package ru.homeserver.photoshare.homeserver.search;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import ru.homeserver.photoshare.homeserver.service.FileService;
import ru.homeserver.photoshare.homeserver.service.SqliteCacheStore;
import java.io.IOException;

@Service
public class VideoSearchService {
    private final PhotoSearchService index;

    public VideoSearchService(FileService files, AppProperties app, PhotoSearchProperties properties,
                              OllamaPhotoAiClient ai, SqliteCacheStore database) throws IOException {
        PhotoAiClient videoAi = new PhotoAiClient() {
            public String describe(byte[] jpeg) throws IOException, InterruptedException { return ai.describeVideo(jpeg); }
            public float[] embed(String text, boolean query) throws IOException, InterruptedException { return ai.embed(text, query); }
            public String signature() { return ai.signature() + "|video-six-frames-v1"; }
        };
        index = new PhotoSearchService(files, app, properties, videoAi, new VideoFrameReader(app), database, true);
    }

    public PhotoSearchService index() { return index; }
    @PostConstruct public void resume() { index.resumeInterruptedRun(); }
    @PreDestroy public void close() { index.close(); }
}
