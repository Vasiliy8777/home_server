package ru.homeserver.photoshare.homeserver.search;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.homeserver.photoshare.homeserver.share.ShareService;

@RestController
@RequestMapping("/api/video-search")
public class VideoSearchController extends PhotoSearchController {
    public VideoSearchController(VideoSearchService video, ShareService shares) {
        super(video.index(), shares);
    }
}
