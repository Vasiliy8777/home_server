package ru.homeserver.photoshare.homeserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "app.video")
public class VideoPreviewProperties {

    private Path hlsCacheDir;
    private String ffmpegPath;
    private String ffprobePath;

    private int hlsSegmentSeconds = 4;
    private int previewWidth = 1920;
    private String previewVideoBitrate = "5000k";
    private String previewAudioBitrate = "128k";
    /*private int hlsSegmentSeconds = 4;
    private int previewWidth = 1280;
    private String previewVideoBitrate = "3500k";
    private String previewAudioBitrate = "128k";*/

    public Path getHlsCacheDir() {
        return hlsCacheDir;
    }

    public void setHlsCacheDir(Path hlsCacheDir) {
        this.hlsCacheDir = hlsCacheDir;
    }

    public String getFfmpegPath() {
        return ffmpegPath;
    }

    public void setFfmpegPath(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    public String getFfprobePath() {
        return ffprobePath;
    }

    public void setFfprobePath(String ffprobePath) {
        this.ffprobePath = ffprobePath;
    }

    public int getHlsSegmentSeconds() {
        return hlsSegmentSeconds;
    }

    public void setHlsSegmentSeconds(int hlsSegmentSeconds) {
        this.hlsSegmentSeconds = hlsSegmentSeconds;
    }

    public int getPreviewWidth() {
        return previewWidth;
    }

    public void setPreviewWidth(int previewWidth) {
        this.previewWidth = previewWidth;
    }

    public String getPreviewVideoBitrate() {
        return previewVideoBitrate;
    }

    public void setPreviewVideoBitrate(String previewVideoBitrate) {
        this.previewVideoBitrate = previewVideoBitrate;
    }

    public String getPreviewAudioBitrate() {
        return previewAudioBitrate;
    }

    public void setPreviewAudioBitrate(String previewAudioBitrate) {
        this.previewAudioBitrate = previewAudioBitrate;
    }

}
