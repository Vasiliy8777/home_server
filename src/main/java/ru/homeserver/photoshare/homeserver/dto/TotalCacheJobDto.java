package ru.homeserver.photoshare.homeserver.dto;

public class TotalCacheJobDto {
    public boolean running;
    public boolean paused;
    public boolean finished;

    public long totalFolders;
    public long processedFolders;

    public long totalFiles;
    public long processedFiles;

    public String currentPath = "";
    public String stage = "Ожидание";

    public int progress;

    public long totalThumbnails;
    public long processedThumbnails;
}
