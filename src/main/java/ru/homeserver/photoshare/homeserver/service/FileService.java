package ru.homeserver.photoshare.homeserver.service;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import ru.homeserver.photoshare.homeserver.dto.FileItemDto;
import ru.homeserver.photoshare.homeserver.dto.FolderNodeDto;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
    private final Path rootPath;
    private static final List<String> HIDDEN_DIRS = List.of(
            ".thumbnails",
            ".upload_tmp"
    );
    public FileService(@Value("${app.storage-root}") String storageRoot) throws IOException {
        /*
         * Paths.get(storageRoot) создает Path из строки.
         *
         * toAbsolutePath() -> превращает путь в абсолютный
         * normalize() -> убирает лишние "." и ".."
         *
         * Пример:
         * D:/MediaLibrary/../MediaLibrary/photos
         * ->
         * D:/MediaLibrary/photos
         */
        this.rootPath = Paths.get(storageRoot).toAbsolutePath().normalize();


        /*
         * Создаем корневую папку, если ее еще нет.
         */
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
    public List<FileItemDto> list(String relativePath) throws IOException {
        /*
         * resolveSafe() делает две вещи:
         * 1) строит реальный путь на диске
         * 2) проверяет, что он не выходит за rootPath
         */
        Path current = resolveSafe(relativePath);

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
            stream.filter(path -> !HIDDEN_DIRS.contains(path.getFileName().toString())).sorted(
                            Comparator
                                    /*
                                     * Хотим, чтобы сначала шли папки, потом файлы.
                                     *
                                     * Files.isDirectory(p) -> true для папки
                                     * Но мы сравниваем !Files.isDirectory(p),
                                     * чтобы папки стали "меньше" файлов при сортировке.
                                     */
                                    .comparing((Path p) -> !Files.isDirectory(p))

                                    /*
                                     * Внутри групп сортируем по имени без учета регистра
                                     */
                                    .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                    )
                    .forEach(path -> {
                        try {
                            boolean isDir = Files.isDirectory(path);

                            /*
                             * rootPath.relativize(path)
                             * превращает абсолютный путь в относительный относительно корня.
                             *
                             * Например:
                             * rootPath = D:/MediaLibrary
                             * path     = D:/MediaLibrary/photos/a.jpg
                             * result   = photos/a.jpg
                             */
                            Path rel = rootPath.relativize(path);

                            /*
                             * replace("\\", "/")
                             * нужен, чтобы на Windows слеши были единообразные
                             * и фронтенду было проще работать.
                             */
                            String relStr = rel.toString().replace("\\", "/");

                            /*
                             * Размер файла.
                             * Для директории поставим 0.
                             */
                            long size = isDir ? 0L : Files.size(path);

                            /*
                             * Определяем логический тип файла.
                             */
                            String type = detectType(path, isDir);

                            /*
                             * previewUrl —
                             * по какому URL браузер сможет открыть сам файл.
                             *
                             * Для папки такого URL нет.
                             */
                            //String previewUrl = isDir ? null : "/api/files/raw?path=" + encodePath(relStr);
                            String previewUrl = null;
                            String downloadUrl = isDir ? null : "/api/files/download?path=" + encodePath(relStr);
                            String thumbnailUrl = null;

                            if (!isDir) {
                                if ("image".equals(type)) {
                                    /*previewUrl = "/api/files/raw?path=" + encodePath(relStr);
                                    thumbnailUrl = previewUrl;*/
                                    previewUrl = "/api/files/raw?path=" + encodePath(relStr);
                                    thumbnailUrl = "/api/files/image-thumbnail?path=" + encodePath(relStr);
                                } else if ("video".equals(type)) {
                                    previewUrl = "/api/files/stream?path=" + encodePath(relStr);
                                    thumbnailUrl = "/api/files/video-thumbnail?path=" + encodePath(relStr);
                                } else {
                                    previewUrl = "/api/files/raw?path=" + encodePath(relStr);
                                }
                            }
                            /*
                             * Создаем DTO и добавляем в результат
                             */
                            result.add(new FileItemDto(
                                    path.getFileName().toString(),
                                    relStr,
                                    isDir,
                                    size,
                                    type,
                                    previewUrl,
                                    thumbnailUrl,
                                    downloadUrl
                            ));
                        } catch (IOException e) {
                            /*
                             * Внутри stream.forEach checked exceptions неудобны,
                             * поэтому оборачиваем в RuntimeException.
                             */
                            throw new RuntimeException(e);
                        }
                    });
        }

        return result;
    }
    public FolderNodeDto getFolderTree() throws IOException {
        return buildFolderNode(rootPath);
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
        String relativePath = rootPath.equals(folder)
                ? ""
                : rootPath.relativize(folder).toString().replace("\\", "/");

        List<FolderNodeDto> children;

        try (Stream<Path> stream = Files.list(folder)) {
            children = stream
                    .filter(Files::isDirectory)
                    /*.filter(path -> !path.getFileName().toString().equals(".thumbnails"))*/
                    .filter(path -> !HIDDEN_DIRS.contains(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .map(path -> {
                        try {
                            return buildFolderNode(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList());
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
    public Path resolveSafe(String relativePath) {
        /*
         * Если путь null или пустой,
         * считаем, что имеется в виду корень.
         */
        String clean = relativePath == null ? "" : relativePath.trim();

        Path resolved = clean.isEmpty()
                ? rootPath
                : rootPath.resolve(clean).normalize();

        /*
         * Проверяем, что путь остался внутри разрешенного rootPath.
         */
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
    private void ensureInsideRoot(Path path) {
        if (!path.startsWith(rootPath)) {
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

        String ext = getExtension(path.getFileName().toString()).toLowerCase();

        if (isImageExtension(ext)) {
            return "image";
        }

        if (isVideoExtension(ext)) {
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
            case "mp4", "mov", "avi", "mkv", "webm", "m4v", "ogv" -> true;
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
}