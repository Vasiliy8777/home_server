package ru.homeserver.photoshare.homeserver.dto;

import java.util.List;

public class FolderPrepareJob {
    public String id;
    public String path;

    public volatile boolean ready;
    public volatile boolean complete;
    public volatile int progress;

    public String stage = "Подготовка папки";
    public List<FileItemDto> items;
    public volatile String databaseFolder;
    public int processed = 0;
    public int total = 0;
    public String sortField = "name";
    public String sortDirection = "asc";
}
