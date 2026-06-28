package ru.homeserver.photoshare.homeserver.share;

public enum SharePermission {
    VIEW,
    DOWNLOAD,
    UPLOAD,
    MANAGE;

    public boolean canView() {
        return true;
    }

    public boolean canDownload() {
        return this == DOWNLOAD || this == MANAGE;
    }

    public boolean canUpload() {
        return this == UPLOAD || this == MANAGE;
    }

    public boolean canDelete() {
        return this == MANAGE;
    }
}