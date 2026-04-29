package ru.homeserver.photoshare.homeserver.dto;

public class RenamePreviewSessionDto {
    private String previewId;
    private String originalPath;
    private String renamedPath;
    private String status;

    public String getPreviewId() {
        return previewId;
    }

    public void setPreviewId(String previewId) {
        this.previewId = previewId;
    }

    public String getOriginalPath() {
        return originalPath;
    }

    public void setOriginalPath(String originalPath) {
        this.originalPath = originalPath;
    }

    public String getRenamedPath() {
        return renamedPath;
    }

    public void setRenamedPath(String renamedPath) {
        this.renamedPath = renamedPath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
