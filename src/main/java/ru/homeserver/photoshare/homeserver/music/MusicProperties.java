package ru.homeserver.photoshare.homeserver.music;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.music")
public class MusicProperties {
    private String root = "K:/muz_gen";
    private String aceUrl = "http://127.0.0.1:8001";
    private String ollamaUrl = "http://127.0.0.1:11434";
    private String lyricsModel = "qwen3:14b";
    private int timeoutSeconds = 3600;
    private boolean autoStart = true;
    private String ffmpegPath = "ffmpeg";
    private String ffprobePath = "ffprobe";
    public String getFfmpegPath() { return ffmpegPath; }
    public void setFfmpegPath(String value) { ffmpegPath = value; }
    public String getFfprobePath() { return ffprobePath; }
    public void setFfprobePath(String value) { ffprobePath = value; }
    public boolean isAutoStart() { return autoStart; }
    public void setAutoStart(boolean value) { autoStart = value; }
    public String getRoot() { return root; }
    public void setRoot(String value) { root = value; }
    public String getAceUrl() { return aceUrl; }
    public void setAceUrl(String value) { aceUrl = value; }
    public String getOllamaUrl() { return ollamaUrl; }
    public void setOllamaUrl(String value) { ollamaUrl = value; }
    public String getLyricsModel() { return lyricsModel; }
    public void setLyricsModel(String value) { lyricsModel = value; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int value) { timeoutSeconds = value; }
}
