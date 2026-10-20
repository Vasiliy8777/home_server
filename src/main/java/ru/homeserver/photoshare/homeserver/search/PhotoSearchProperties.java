package ru.homeserver.photoshare.homeserver.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.photo-search")
public class PhotoSearchProperties {
    private boolean enabled = true;
    private boolean autoResume = true;
    private int retryAttempts = 4;
    private int retryDelaySeconds = 5;
    public boolean isAutoResume() { return autoResume; }
    public void setAutoResume(boolean value) { autoResume = value; }
    public int getRetryAttempts() { return retryAttempts; }
    public void setRetryAttempts(int value) { retryAttempts = value; }
    public int getRetryDelaySeconds() { return retryDelaySeconds; }
    public void setRetryDelaySeconds(int value) { retryDelaySeconds = value; }

    private String baseUrl = "http://127.0.0.1:11434";
    private String visionModel = "qwen3-vl:4b";
    private String embeddingModel = "embeddinggemma";
    private int timeoutSeconds = 180;
    private double minScore = 0.35;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getVisionModel() { return visionModel; }
    public void setVisionModel(String visionModel) { this.visionModel = visionModel; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public double getMinScore() { return minScore; }
    public void setMinScore(double minScore) { this.minScore = minScore; }
}
