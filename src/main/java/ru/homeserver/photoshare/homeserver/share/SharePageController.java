package ru.homeserver.photoshare.homeserver.share;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class SharePageController {
    private final ShareService shareService;

    public SharePageController(ShareService shareService) {
        this.shareService = shareService;
    }

    @GetMapping("/share/{token}")
    public String sharePage(@PathVariable String token) {
        if (!shareService.isTokenActive(token)) {
            return "forward:/deletedlink.html";
        }

        return "forward:/share.html";
    }
}