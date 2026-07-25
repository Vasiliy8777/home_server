package ru.homeserver.photoshare.homeserver.share;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import ru.homeserver.photoshare.homeserver.util.CloudAccessLogService;

@Controller
public class SharePageController {
    private final ShareService shareService;
    private final CloudAccessLogService cloudAccessLogService;
    public SharePageController(ShareService shareService, CloudAccessLogService cloudAccessLogService) {
        this.shareService = shareService;
        this.cloudAccessLogService = cloudAccessLogService;
    }

    @GetMapping("/share/{token}")
    public String sharePage(@PathVariable String token, HttpServletRequest request) {
        if (!shareService.isTokenActive(token)) {
            cloudAccessLogService.event("SHARE_PAGE_DENIED", request, "token=" + token);
            return "forward:/deletedlink.html";
        }
        cloudAccessLogService.event("SHARE_PAGE_OPEN", request, "token=" + token);

        return "forward:/share.html";
    }
}