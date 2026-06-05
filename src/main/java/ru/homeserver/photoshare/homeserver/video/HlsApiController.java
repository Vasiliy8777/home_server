package ru.homeserver.photoshare.homeserver.video;


import org.springframework.web.bind.annotation.*;
import ru.homeserver.photoshare.homeserver.service.FileService;

import java.nio.file.Path;

@RestController
@RequestMapping("/api/video/hls")
public class HlsApiController {

    private final FileService fileService;
    private final HlsConversionService hlsConversionService;

    public HlsApiController(
            FileService fileService,
            HlsConversionService hlsConversionService
    ) {
        this.fileService = fileService;
        this.hlsConversionService = hlsConversionService;
    }

    @PostMapping("/prepare")
    public HlsConversionState prepare(@RequestParam String path) {
        Path sourceFile = fileService.resolveSafe(path);

        return hlsConversionService.prepareHls(
                sourceFile,
                "/api/video/hls/files"
        );
    }

    @GetMapping("/status")
    public HlsStatus status(@RequestParam String path) {
        Path sourceFile = fileService.resolveSafe(path);
        return hlsConversionService.getStatus(sourceFile);
    }

    @DeleteMapping("/cancel")
    public void cancel(@RequestParam String path) {
        Path sourceFile = fileService.resolveSafe(path);
        hlsConversionService.cancel(sourceFile);
    }
    @GetMapping("/progress")
    public HlsProgressState progress(@RequestParam String path) {
        Path sourceFile = fileService.resolveSafe(path);

        return hlsConversionService.getProgress(sourceFile);
    }
}
