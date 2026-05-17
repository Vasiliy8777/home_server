package ru.homeserver.photoshare.homeserver.service;


import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import ru.homeserver.photoshare.homeserver.dto.FileItemDto;
import ru.homeserver.photoshare.homeserver.dto.FolderNodeDto;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
 * Основной сервис для работы с файловой системой.
 *
 * Он отвечает за:
 * - список файлов
 * - загрузку
 * - создание папок
 * - удаление
 * - безопасное преобразование путей
 */
@Service
public class FileService {

    /*
     * Корневая папка, внутри которой разрешены все операции.
     *
     * Это ключевой элемент безопасности:
     * приложение должно работать только внутри одной разрешенной директории.
     */
    private final MetadataService metadataService;
    private final Path rootPath;
    private volatile FolderNodeDto cachedFolderTree;
    private static final List<String> HIDDEN_DIRS = List.of(
            ".thumbnails",
            ".upload_tmp",
            ".preview_journal",
            ".metadata_cache",
            ".folder_cache",
            ".security",

            "$RECYCLE.BIN",
            "System Volume Information"
    );
    public long countItems(String relativePath) throws IOException {

        Path current = resolveSafe(relativePath);

        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(current)) {

            long count = 0;

            for (Path path : stream) {

                try {

                    String name =
                            path.getFileName().toString();

                    if (HIDDEN_DIRS.contains(name)) {
                        continue;
                    }

                    if (Files.isSymbolicLink(path)) {
                        continue;
                    }

                    count++;

                } catch (Exception ignored) {
                }
            }

            return count;
        }
    }
    public FileService(MetadataService metadataService, AppProperties appProperties) throws IOException {
        this.metadataService = metadataService;
        this.rootPath = Paths.get(appProperties.getStorageRoot()).toAbsolutePath().normalize();
        Files.createDirectories(this.rootPath);
    }
    public Path getRootPath() {
        return rootPath;
    }

    /*
     * Вернуть список файлов и папок в указанной директории.
     *
     * На вход приходит относительный путь внутри rootPath.
     * Например:
     * ""                -> корень
     * "photos"          -> папка photos
     * "photos/2026"     -> подпапка
     */
    public List<FileItemDto> list(String relativePath, int offset, int limit) throws IOException {
        /*
         * resolveSafe() делает две вещи:
         * 1) строит реальный путь на диске
         * 2) проверяет, что он не выходит за rootPath
         */
        Path current = resolveSafe(relativePath);
        String absolutePath;
        /*
         * Проверяем, что папка существует
         */
        if (!Files.exists(current)) {
            throw new NoSuchFileException("Folder does not exist: " + relativePath);
        }

        /*
         * Проверяем, что это именно директория
         */
        if (!Files.isDirectory(current)) {
            throw new IllegalArgumentException("Path is not a directory: " + relativePath);
        }

        List<FileItemDto> result = new ArrayList<>();

        /*
         * Files.list(current) возвращает Stream<Path>
         * только по одному уровню вложенности.
         *
         * То есть он перечисляет именно содержимое папки,
         * но не обходит рекурсивно все подпапки.
         */
        try (Stream<Path> stream = Files.list(current)) {
            /*List<Path> all = stream*/
            List<Path> all = stream
                    .filter(path -> {
                        try {
                            return !Files.isSymbolicLink(path);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .filter(path -> !HIDDEN_DIRS.contains(path.getFileName().toString()))
                    .sorted(
                            Comparator
                                    .comparing((Path p) -> !Files.isDirectory(p))
                                    .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                    )
                    .skip(offset)
                    .limit(limit)
                    .toList();

            for (Path path : all) {
                try {
                    boolean isDir = Files.isDirectory(path);

                    Path rel = rootPath.relativize(path);
                    String relStr = rel.toString().replace("\\", "/");

                    long size = isDir ? 0L : Files.size(path);
                    String type = detectType(path, isDir);

                    String previewUrl = null;
                    String downloadUrl = isDir ? null : "/api/files/download?path=" + encodePath(relStr);
                    String thumbnailUrl = null;

                    if (!isDir) {
                        /*if ("image".equals(type)) {
                            previewUrl = "/api/files/raw?path=" + encodePath(relStr);
                            thumbnailUrl = "/api/files/image-thumbnail?path=" + encodePath(relStr);
                        }*/if ("image".equals(type)) {
                            String lower = relStr.toLowerCase(Locale.ROOT);

                            thumbnailUrl = "/api/files/image-thumbnail?path=" + encodePath(relStr);

                            if (lower.endsWith(".heic") || lower.endsWith(".heif")) {
                                previewUrl = "/api/files/image-thumbnail?path=" + encodePath(relStr);
                            } else {
                                previewUrl = "/api/files/raw?path=" + encodePath(relStr);
                            }
                        } else if ("video".equals(type)) {
                            String lower = relStr.toLowerCase();

                            if (lower.endsWith(".insv") || lower.endsWith(".lrv")) {
                                previewUrl = "/api/files/video-proxy?path=" + encodePath(relStr);
                            } else {
                                previewUrl = "/api/files/stream?path=" + encodePath(relStr);
                            }

                            thumbnailUrl = "/api/files/video-thumbnail?path=" + encodePath(relStr);
                        } else {
                            previewUrl = "/api/files/raw?path=" + encodePath(relStr);
                        }
                    }
                    long modified = Files.getLastModifiedTime(path).toMillis();

                    /*long createdAt = isDir
                            ? modified
                            : metadataService.readCreatedAtMillisCached(path);*/
                    long createdAt = isDir ? 0L : modified;
                    Long fileCount = null;
                    Long folderCount = null;

                    /*long createdAt = modified;*/
                    if (isDir) {
                        long[] counts = countDirectFolderChildren(path);
                        fileCount = counts[0];
                        folderCount = counts[1];
                    }
                    result.add(new FileItemDto(
                            path.getFileName().toString(),
                            relStr,
                            isDir,
                            size,
                            type,
                            previewUrl,
                            thumbnailUrl,
                            downloadUrl,
                            modified,
                            createdAt,
                            fileCount,
                            folderCount
                    ));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return result;
    }
    private FolderNodeDto buildFolderTree() throws IOException {
        return buildFolderNode(rootPath);
    }
    public List<Path> getCachedFolderPaths() {
        FolderNodeDto tree = cachedFolderTree;

        if (tree == null) {
            try {
                rebuildFolderTreeCache();
                tree = cachedFolderTree;
            } catch (Exception e) {
                return List.of(rootPath);
            }
        }

        List<Path> result = new ArrayList<>();
        collectFolderPaths(tree, result);

        return result;
    }
    public void rename(String relativePath, String newName) throws IOException {
        if (!StringUtils.hasText(newName)) {
            throw new IllegalArgumentException("New name is empty");
        }

        if (newName.contains("/") || newName.contains("\\") || newName.contains("..")) {
            throw new IllegalArgumentException("Invalid file name");
        }

        Path source = resolveSafe(relativePath);

        if (source.getFileName().toString().equalsIgnoreCase(newName)) {
            return;
        }

        Path target = source.resolveSibling(newName).normalize();
        ensureInsideRoot(target);

        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
    private void collectFolderPaths(FolderNodeDto node, List<Path> result) {
        String relativePath = node.relativePath();

        /*Path path = relativePath == null || relativePath.isBlank()
                ? rootPath
                : rootPath.resolve(relativePath).normalize();*/
        Path path = resolveSafe(relativePath);

        result.add(path);

        if (node.children() == null) {
            return;
        }

        for (FolderNodeDto child : node.children()) {
            collectFolderPaths(child, result);
        }
    }
    private long[] countDirectFolderChildren(Path folder) {
        long files = 0;
        long folders = 0;

        try (Stream<Path> stream = Files.list(folder)) {
            for (Path child : stream.toList()) {
                String name = child.getFileName().toString();

                if (HIDDEN_DIRS.contains(name)) {
                    continue;
                }

                /*if (Files.isDirectory(child)) {*/
                if (Files.isSymbolicLink(child)) {
                    continue;
                }

                if (Files.isDirectory(child)) {
                    folders++;
                } else {
                    files++;
                }
            }
        } catch (IOException e) {
            return new long[]{0, 0};
        }

        return new long[]{files, folders};
    }

    public void move(String sourceRelativePath, String targetDirectoryRelativePath) throws IOException {
        Path source = resolveSafe(sourceRelativePath);
        Path targetDirectory = resolveSafe(targetDirectoryRelativePath);

        if (!Files.exists(source)) {
            throw new NoSuchFileException("Source does not exist: " + sourceRelativePath);
        }

        if (!Files.exists(targetDirectory)) {
            throw new NoSuchFileException("Target directory does not exist: " + targetDirectoryRelativePath);
        }

        if (!Files.isDirectory(targetDirectory)) {
            throw new IllegalArgumentException("Target path is not a directory");
        }

        Path target = targetDirectory.resolve(source.getFileName()).normalize();
        ensureInsideRoot(target);

        if (source.equals(target)) {
            throw new IllegalArgumentException("Source and target are the same");
        }

        if (Files.isDirectory(source) && target.startsWith(source)) {
            throw new IllegalArgumentException("Cannot move a folder into itself");
        }

        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
    private FolderNodeDto buildFolderNode(Path folder) throws IOException {

        String folderName = folder.getFileName() != null
                ? folder.getFileName().toString()
                : "";

        if (HIDDEN_DIRS.contains(folderName)) {
            return null;
        }

        String relativePath = rootPath.equals(folder)
                ? ""
                : rootPath.relativize(folder).toString().replace("\\", "/");

        List<FolderNodeDto> children;

        try (Stream<Path> stream = Files.list(folder)) {

            children = stream
                    /*.filter(Files::isDirectory)*/
                    .filter(path -> {
                        try {
                            return Files.isDirectory(path)
                                    && !Files.isSymbolicLink(path);
                                    /*&& !Files.isHidden(path);*/
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .filter(path -> !HIDDEN_DIRS.contains(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path ->
                            path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .map(path -> {
                        try {
                            return buildFolderNode(path);
                        } catch (AccessDeniedException e) {
                            return null;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

        } catch (AccessDeniedException e) {
            return null;
        }

        return new FolderNodeDto(
                rootPath.equals(folder) ? "/" : folder.getFileName().toString(),
                relativePath,
                children
        );
    }

    /*
     * Создание папки внутри указанной директории.
     */
    public void createFolder(String parentRelativePath, String folderName) throws IOException {
        /*
         * StringUtils.hasText(...) проверяет:
         * - не null
         * - не пустая строка
         * - не строка из одних пробелов
         */
        if (!StringUtils.hasText(folderName)) {
            throw new IllegalArgumentException("Folder name is empty");
        }

        Path parent = resolveSafe(parentRelativePath);

        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException("Parent path is not a directory");
        }

        /*
         * Формируем путь новой папки.
         */
        Path newFolder = parent.resolve(folderName).normalize();

        /*
         * Дополнительная защита:
         * даже если folderName будет содержать попытку выхода через ../
         * после normalize и ensureInsideRoot это будет остановлено.
         */
        ensureInsideRoot(newFolder);

        /*
         * createDirectories создаст всю цепочку папок, если нужно.
         */
        Files.createDirectories(newFolder);
    }

    /*
     * Загрузка одного или нескольких файлов в папку.
     *
     * MultipartFile — специальный интерфейс Spring,
     * представляющий файл, пришедший в HTTP multipart/form-data запросе.
     */
    public void upload(String relativePath, MultipartFile[] files) throws IOException {
        Path targetDir = resolveSafe(relativePath);

        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
        }

        if (!Files.isDirectory(targetDir)) {
            throw new IllegalArgumentException("Upload target is not a directory");
        }

        for (MultipartFile file : files) {
            /*
             * Пропускаем null или пустые файлы
             */
            if (file == null || file.isEmpty()) {
                continue;
            }

            /*
             * getOriginalFilename() может теоретически содержать путь,
             * поэтому мы берем только конечное имя.
             *
             * Paths.get(...).getFileName().toString()
             * позволяет отсечь опасные части пути.
             */
            String originalName = Paths.get(file.getOriginalFilename()).getFileName().toString();

            if (!StringUtils.hasText(originalName)) {
                continue;
            }

            /*
             * Итоговый путь, куда запишем файл.
             */
            Path destination = targetDir.resolve(originalName).normalize();

            ensureInsideRoot(destination);

            /*
             * file.getInputStream() возвращает поток данных загруженного файла.
             *
             * Files.copy(...) записывает этот поток на диск.
             *
             * StandardCopyOption.REPLACE_EXISTING означает:
             * если файл уже есть — перезаписать.
             */
            try (var in = file.getInputStream()) {
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /*
     * Удаление файла или папки.
     */
    public void delete(String relativePath) throws IOException {
        Path path = resolveSafe(relativePath);

        if (!Files.exists(path)) {
            return;
        }

        if (Files.isDirectory(path)) {
            /*
             * Files.walk(path) обходит путь рекурсивно:
             * сначала родитель, потом дети.
             *
             * Но удалять папку нужно наоборот:
             * сначала файлы и вложенные папки,
             * потом саму папку.
             *
             * Поэтому сортируем в reverseOrder().
             */
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        } else {
            Files.deleteIfExists(path);
        }
    }

    /*
     * Безопасное преобразование относительного пути в абсолютный Path.
     *
     * Это один из самых важных методов во всем проекте.
     */
    /*public Path resolveSafe(String relativePath) {
        *//*
         * Если путь null или пустой,
         * считаем, что имеется в виду корень.
         *//*
        String clean = relativePath == null ? "" : relativePath.trim();

        Path resolved = clean.isEmpty()
                ? rootPath
                : rootPath.resolve(clean).normalize();

        *//*
         * Проверяем, что путь остался внутри разрешенного rootPath.
         *//*
        ensureInsideRoot(resolved);

        return resolved;
    }*/
    public Path resolveSafe(String relativePath) {
        String clean = relativePath == null ? "" : relativePath.trim();

        clean = clean.replace("\\", "/");

        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }

        if (clean.contains("\0")) {
            throw new IllegalArgumentException("Invalid path");
        }

        Path resolved = clean.isEmpty()
                ? rootPath
                : rootPath.resolve(clean).normalize();

        ensureInsideRoot(resolved);

        return resolved;
    }

    /*
     * Защита от выхода за пределы разрешенной папки.
     *
     * Это защита от path traversal attack.
     *
     * Например, если пользователь попытается передать:
     * ../../../Windows/System32
     *
     * После resolve + normalize это превратится в реальный путь,
     * и если он не начинается с rootPath, значит доступ запрещен.
     */
   /* private void ensureInsideRoot(Path path) {
        if (!path.startsWith(rootPath)) {
            throw new IllegalArgumentException("Access outside root folder is forbidden");
        }
    }*/
    private void ensureInsideRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();

        if (!normalized.startsWith(rootPath)) {
            throw new IllegalArgumentException("Access outside root folder is forbidden");
        }
    }
    /*
     * Определение логического типа файла.
     *
     * Это нужно не столько backend, сколько frontend,
     * чтобы он понял:
     * - рисовать ли img
     * - рисовать ли thumbnail видео
     * - показывать ли иконку файла
     */
    private String detectType(Path path, boolean isDir) {

        if (isDir) {
            return "directory";
        }

        String ext = getExtension(path.getFileName().toString()).toLowerCase();

        if (isImageExtension(ext)) {
            return "image";
        }

        if (isVideoExtension(ext)) {
            return "video";
        }

        String contentType = null;

        try {
            contentType = Files.probeContentType(path);
        } catch (IOException ignored) {
        }

        if (contentType == null) {
            contentType = URLConnection.guessContentTypeFromName(path.getFileName().toString());
        }

        if (contentType == null) {
            return "file";
        }

        if (contentType.startsWith("image/")) {
            return "image";
        }

        if (contentType.startsWith("video/")) {
            return "video";
        }

        return "file";
    }

    /*
     * Очень простое экранирование пробелов.
     *
     * Полноценнее было бы использовать URL encoding целиком,
     * но для минимального примера этого достаточно.
     */
    private String encodePath(String path) {
        return path.replace(" ", "%20");
    }

    private boolean isVideoExtension(String ext) {
        return switch (ext.toLowerCase()) {
            case "mp4", "mov", "avi", "mkv", "webm", "m4v", "ogv", "insv", "lrv" -> true;
            default -> false;
        };
    }

    private boolean isImageExtension(String ext) {
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "tif", "tiff",
                 "avif", "heic", "heif", "raw", "cr2", "cr3", "nef", "arw", "dng" -> true;
            default -> false;
        };
    }

    private String getExtension(String filename) {
        int index = filename.lastIndexOf('.');
        return index >= 0 ? filename.substring(index + 1) : "";
    }
    @PostConstruct
    public void initFolderTreeCache() {
        try {
            rebuildFolderTreeCache();
            System.out.println("Folder tree cache built on startup");
        } catch (Exception e) {
            System.out.println("Failed to build folder tree cache on startup: " + e.getMessage());
        }
    }

    public FolderNodeDto getFolderTreeCached() throws IOException {
        if (cachedFolderTree == null) {
            rebuildFolderTreeCache();
        }

        return cachedFolderTree;
    }

    public synchronized void rebuildFolderTreeCache() throws IOException {
        this.cachedFolderTree = buildFolderTree();
    }
}