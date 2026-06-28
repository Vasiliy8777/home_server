package ru.homeserver.photoshare.homeserver.share;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/share")
public class ShareAdminController {

    private final ShareService shareService;

    public ShareAdminController(ShareService shareService) {
        this.shareService = shareService;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody ShareCreateRequest request,
            HttpServletRequest httpRequest
    ) throws IOException {
        return ResponseEntity.ok(
                shareService.create(request, baseUrl(httpRequest))
        );
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest httpRequest) throws IOException {
        return ResponseEntity.ok(
                shareService.list(baseUrl(httpRequest))
        );
    }

    @PatchMapping("/{token}")
    public ResponseEntity<?> update(
            @PathVariable String token,
            @RequestBody ShareUpdateRequest request,
            HttpServletRequest httpRequest
    ) throws IOException {
        return ResponseEntity.ok(
                shareService.update(token, request, baseUrl(httpRequest))
        );
    }

    @DeleteMapping("/{token}")
    public ResponseEntity<?> delete(@PathVariable String token) throws IOException {
        shareService.deleteLink(token);
        return ResponseEntity.ok().build();
    }

    private String baseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();

        boolean defaultPort =
                ("http".equals(scheme) && port == 80)
                        || ("https".equals(scheme) && port == 443);

        return defaultPort
                ? scheme + "://" + host
                : scheme + "://" + host + ":" + port;
    }
}