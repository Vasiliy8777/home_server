package ru.homeserver.photoshare.homeserver.share;

public enum SharePermission {
    VIEW,
    DOWNLOAD,
    UPLOAD,
    MANAGE;

    public boolean canDownload() {
        return this == DOWNLOAD || this == UPLOAD || this == MANAGE;
    }

    public boolean canUpload() {
        return this == UPLOAD || this == MANAGE;
    }

    public boolean canDelete() {
        return this == MANAGE;
    }
}
