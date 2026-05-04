package ru.homeserver.photoshare.homeserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String storageRoot;
    private String metadataCacheDir;
    private String thumbnailCacheDir;
    private String previewCacheDir;

    private String ffmpegPath;
    private String ffprobePath;
    private String magickPath;

    private Security security = new Security();

    public static class Security {
        private String username;
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public String getStorageRoot() { return storageRoot; }
    public void setStorageRoot(String storageRoot) { this.storageRoot = storageRoot; }

    public String getMetadataCacheDir() { return metadataCacheDir; }
    public void setMetadataCacheDir(String metadataCacheDir) { this.metadataCacheDir = metadataCacheDir; }

    public String getThumbnailCacheDir() { return thumbnailCacheDir; }
    public void setThumbnailCacheDir(String thumbnailCacheDir) { this.thumbnailCacheDir = thumbnailCacheDir; }

    public String getPreviewCacheDir() { return previewCacheDir; }
    public void setPreviewCacheDir(String previewCacheDir) { this.previewCacheDir = previewCacheDir; }

    public String getFfmpegPath() { return ffmpegPath; }
    public void setFfmpegPath(String ffmpegPath) { this.ffmpegPath = ffmpegPath; }

    public String getFfprobePath() { return ffprobePath; }
    public void setFfprobePath(String ffprobePath) { this.ffprobePath = ffprobePath; }

    public String getMagickPath() { return magickPath; }
    public void setMagickPath(String magickPath) { this.magickPath = magickPath; }

    public Security getSecurity() { return security; }
    public void setSecurity(Security security) { this.security = security; }
}
