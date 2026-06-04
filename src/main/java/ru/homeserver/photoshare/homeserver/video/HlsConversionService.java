package ru.homeserver.photoshare.homeserver.video;

import org.springframework.stereotype.Service;
import ru.homeserver.photoshare.homeserver.config.VideoPreviewProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class HlsConversionService {

    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    private final VideoPreviewProperties props;
    private final VideoCacheKeyService cacheKeyService;

    private final Map<String, HlsStatus> statuses = new ConcurrentHashMap<>();
    public HlsConversionService(
            VideoPreviewProperties props,
            VideoCacheKeyService cacheKeyService
    ) {
        this.props = props;
        this.cacheKeyService = cacheKeyService;
    }

    public HlsConversionState prepareHls(Path sourceFile, String publicBaseUrl) {
        String key = cacheKeyService.cacheKey(sourceFile);

        Path folder = props.getHlsCacheDir().resolve(key);
        Path playlist = folder.resolve("playlist.m3u8");
        Path init = folder.resolve("init.mp4");

        String playlistUrl = publicBaseUrl + "/" + key + "/playlist.m3u8";

        Process process = processes.get(key);
        boolean running = process != null && process.isAlive();

        boolean hasInit = Files.exists(init);
        boolean hasPlaylist = Files.exists(playlist);
        boolean enoughSegments = hasEnoughSegments(folder);
        boolean anySegments = hasAnySegment(folder);
        boolean finished = hasPlaylist && playlistHasEndlist(playlist);

        if (finished) {
            statuses.put(key, HlsStatus.READY);
            return new HlsConversionState(HlsStatus.READY, playlistUrl, "HLS ready");
        }

        if (running && enoughSegments) {
            return new HlsConversionState(HlsStatus.PLAYABLE, playlistUrl, "HLS playable");
        }

        if (running) {
            return new HlsConversionState(HlsStatus.RUNNING, playlistUrl, "HLS running");
        }

        try {
            Files.createDirectories(folder);

            if (anySegments && hasInit) {
                rebuildPlaylist(folder, false);
                statuses.put(key, HlsStatus.RUNNING);
                startFfmpegResume(sourceFile, folder, key);

                return new HlsConversionState(
                        enoughSegments ? HlsStatus.PLAYABLE : HlsStatus.RUNNING,
                        playlistUrl,
                        "HLS resumed"
                );
            }
            /*if (anySegments && hasInit) {
                clearBrokenHls(folder);

                statuses.put(key, HlsStatus.RUNNING);
                startFfmpeg(sourceFile, folder, key);

                return new HlsConversionState(
                        HlsStatus.RUNNING,
                        playlistUrl,
                        "HLS restarted safely"
                );
            }*/

            clearBrokenHls(folder);

            statuses.put(key, HlsStatus.RUNNING);
            startFfmpeg(sourceFile, folder, key);

            return new HlsConversionState(
                    HlsStatus.RUNNING,
                    playlistUrl,
                    "HLS started"
            );

        } catch (Exception e) {
            statuses.put(key, HlsStatus.FAILED);
            return new HlsConversionState(HlsStatus.FAILED, null, e.getMessage());
        }
    }
    public HlsStatus getStatus(Path sourceFile) {
        String key = cacheKeyService.cacheKey(sourceFile);

        Path folder = props.getHlsCacheDir().resolve(key);
        Path playlist = folder.resolve("playlist.m3u8");
        Path init = folder.resolve("init.mp4");

        boolean playable = Files.exists(playlist)
                && Files.exists(init)
                && hasAnySegment(folder);

        boolean finished = playable && playlistHasEndlist(playlist);

        Process process = processes.get(key);
        boolean running = process != null && process.isAlive();

        if (finished) {
            statuses.put(key, HlsStatus.READY);
            return HlsStatus.READY;
        }

        if (running && playable) {
            return HlsStatus.PLAYABLE;
        }

        if (running) {
            return HlsStatus.RUNNING;
        }

        return statuses.getOrDefault(key, HlsStatus.NOT_STARTED);
    }

    private boolean playlistHasEndlist(Path playlist) {
        try {
            return Files.readString(playlist).contains("#EXT-X-ENDLIST");
        } catch (Exception e) {
            return false;
        }
    }
    private boolean hasEnoughSegments(Path folder) {
        try (var stream = Files.list(folder)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".m4s"))
                    .limit(3)
                    .count() >= 3;
        } catch (Exception e) {
            return false;
        }
    }
    private void rebuildPlaylist(Path folder, boolean endList) {
        try {
            Path playlist = folder.resolve("playlist.m3u8");

            var segments = Files.list(folder)
                    .filter(p -> p.getFileName().toString().endsWith(".m4s"))
                    .sorted()
                    .toList();

            StringBuilder sb = new StringBuilder();

            sb.append("#EXTM3U\n");
            sb.append("#EXT-X-VERSION:7\n");
            sb.append("#EXT-X-TARGETDURATION:")
                    .append(props.getHlsSegmentSeconds() + 1)
                    .append("\n");
            sb.append("#EXT-X-MEDIA-SEQUENCE:0\n");
            sb.append("#EXT-X-PLAYLIST-TYPE:EVENT\n");
            sb.append("#EXT-X-MAP:URI=\"init.mp4\"\n");

            for (Path segment : segments) {
                sb.append("#EXTINF:")
                        .append(props.getHlsSegmentSeconds())
                        .append(".000000,\n");
                sb.append(segment.getFileName()).append("\n");
            }

            if (endList) {
                sb.append("#EXT-X-ENDLIST\n");
            }

            Files.writeString(playlist, sb.toString());

        } catch (Exception e) {
            System.out.println("[HLS] Cannot rebuild playlist: " + e.getMessage());
        }
    }
    private void clearBrokenHls(Path folder) {
        try {
            if (!Files.exists(folder)) return;

            try (var stream = Files.list(folder)) {
                stream
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.endsWith(".m3u8")
                                    || name.endsWith(".m4s")
                                    || name.endsWith(".mp4");
                        })
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                            }
                        });
            }
        } catch (Exception e) {
            System.out.println("[HLS] Cannot clear broken HLS: " + e.getMessage());
        }
    }
    private boolean hasAnySegment(Path folder) {
        try (var stream = Files.list(folder)) {
            return stream.anyMatch(path ->
                    path.getFileName().toString().endsWith(".m4s")
            );
        } catch (Exception e) {
            return false;
        }
    }
    private void startFfmpegResume(Path sourceFile, Path folder, String key) throws IOException {
        long segmentCount = countSegments(folder);

        long startSeconds = Math.max(0, segmentCount * props.getHlsSegmentSeconds());

        ProcessBuilder pb = new ProcessBuilder(
                props.getFfmpegPath(),
                "-y",

                "-ss", String.valueOf(startSeconds),
                "-i", sourceFile.toAbsolutePath().toString(),

                "-vf", "scale=" + props.getPreviewWidth() + ":-2",

                "-c:v", "h264_nvenc",
                "-preset", "p4",
                "-b:v", props.getPreviewVideoBitrate(),
                "-maxrate", props.getPreviewVideoBitrate(),
                "-bufsize", "10000k",
                "-pix_fmt", "yuv420p",

               /* "-g", String.valueOf(props.getHlsSegmentSeconds() * 30),
                "-keyint_min", String.valueOf(props.getHlsSegmentSeconds() * 30),
                "-sc_threshold", "0",
                "-force_key_frames", "expr:gte(t,n_forced*" + props.getHlsSegmentSeconds() + ")",*/

                "-c:a", "aac",
                "-b:a", props.getPreviewAudioBitrate(),

                "-avoid_negative_ts", "make_zero",
                "-reset_timestamps", "1",

                "-f", "hls",
                "-hls_time", String.valueOf(props.getHlsSegmentSeconds()),
                "-hls_playlist_type", "event",

                "-hls_list_size", "0",
                "-hls_flags", "append_list+temp_file+independent_segments",

                "-hls_segment_type", "fmp4",
                "-hls_fmp4_init_filename", "init.mp4",

                "-start_number", String.valueOf(segmentCount),
                "-hls_segment_filename", "seg_%06d.m4s",

                "playlist.m3u8"
        );

        startProcess(pb, folder, key);
    }
    private void startProcess(ProcessBuilder pb, Path folder, String key) throws IOException {
        pb.redirectErrorStream(true);
        pb.directory(folder.toFile());

        Process process = pb.start();
        processes.put(key, process);

        Thread.ofVirtual().start(() -> {
            try (var scanner = new java.util.Scanner(process.getInputStream())) {
                while (scanner.hasNextLine()) {
                    System.out.println("[FFMPEG] " + scanner.nextLine());
                }

                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    statuses.put(key, HlsStatus.READY);
                } else {
                    statuses.put(key, HlsStatus.FAILED);
                }
            } catch (Exception e) {
                statuses.put(key, HlsStatus.FAILED);
            } finally {
                processes.remove(key);
            }
        });
    }
    private void startFfmpeg(Path sourceFile, Path folder, String key) throws IOException {
        Path playlist = folder.resolve("playlist.m3u8");
        Path initFile = folder.resolve("init.mp4");

        ProcessBuilder pb = new ProcessBuilder(
                props.getFfmpegPath(),
                "-y",

                "-i", sourceFile.toAbsolutePath().toString(),

                "-vf", "scale=" + props.getPreviewWidth() + ":-2",

                "-c:v", "h264_nvenc",
                "-preset", "p4",
                "-b:v", props.getPreviewVideoBitrate(),
                "-maxrate", props.getPreviewVideoBitrate(),
                "-bufsize", "10000k",
                "-pix_fmt", "yuv420p",

                /*"-g", String.valueOf(props.getHlsSegmentSeconds() * 30),
                "-keyint_min", String.valueOf(props.getHlsSegmentSeconds() * 30),
                "-sc_threshold", "0",
                "-force_key_frames", "expr:gte(t,n_forced*" + props.getHlsSegmentSeconds() + ")",*/

                "-c:a", "aac",
                "-b:a", props.getPreviewAudioBitrate(),

                "-avoid_negative_ts", "make_zero",
                "-reset_timestamps", "1",

                "-f", "hls",
                "-hls_time", String.valueOf(props.getHlsSegmentSeconds()),
                "-hls_playlist_type", "event", //"vod", //vod хорош после полной готовности

                "-hls_list_size", "0",
                "-hls_flags", "append_list+temp_file+independent_segments",

                "-hls_segment_type", "fmp4",
                "-hls_fmp4_init_filename", "init.mp4",
                "-hls_segment_filename", "seg_%06d.m4s",

                "playlist.m3u8"
        );

        pb.redirectErrorStream(true);
        pb.directory(folder.toFile());
        Process process = pb.start();

        processes.put(key, process);

        Thread.ofVirtual().start(() -> {
            try (var scanner = new java.util.Scanner(process.getInputStream())) {

                while (scanner.hasNextLine()) {
                    System.out.println("[FFMPEG] " + scanner.nextLine());
                }

                int exitCode = process.waitFor();

                System.out.println("[FFMPEG] EXIT = " + exitCode);

                Path readyInitFile = folder.resolve("init.mp4");
                Path readyPlaylist = folder.resolve("playlist.m3u8");

                if (exitCode == 0
                        && Files.exists(readyPlaylist)
                        && Files.size(readyPlaylist) > 0
                        && Files.exists(readyInitFile)
                        && Files.size(readyInitFile) > 0) {
                    statuses.put(key, HlsStatus.READY);
                } else {
                    System.out.println("[HLS] Not ready:");
                    System.out.println("[HLS] playlist exists = " + Files.exists(readyPlaylist));
                    System.out.println("[HLS] init exists = " + Files.exists(readyInitFile));
                    statuses.put(key, HlsStatus.FAILED);
                }
            } catch (Exception e) {
                e.printStackTrace();
                statuses.put(key, HlsStatus.FAILED);
            } finally {
                processes.remove(key);
            }
        });
    }
    public void cancel(Path sourceFile) {
        String key = cacheKeyService.cacheKey(sourceFile);

        Process process = processes.remove(key);

        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }

        Path folder = props.getHlsCacheDir().resolve(key);

        String deletedSegment = deleteLastSegmentAndReturnName(folder);

        if (deletedSegment != null) {
            removeSegmentFromPlaylist(folder.resolve("playlist.m3u8"), deletedSegment);
        }

        statuses.put(key, HlsStatus.PAUSED);
    }
    private long countSegments(Path folder) {
        try (var stream = Files.list(folder)) {
            return stream.filter(path ->
                    path.getFileName().toString().endsWith(".m4s")
            ).count();
        } catch (Exception e) {
            return 0;
        }
    }
    private String deleteLastSegmentAndReturnName(Path folder) {
        try (var stream = Files.list(folder)) {
            var last = stream
                    .filter(p -> p.getFileName().toString().endsWith(".m4s"))
                    .sorted()
                    .reduce((a, b) -> b);

            if (last.isPresent()) {
                String name = last.get().getFileName().toString();
                Files.deleteIfExists(last.get());
                return name;
            }
        } catch (Exception e) {
            System.out.println("[HLS] Cannot delete last segment: " + e.getMessage());
        }

        return null;
    }
    private void removeSegmentFromPlaylist(Path playlist, String segmentName) {
        try {
            if (!Files.exists(playlist)) return;

            var lines = Files.readAllLines(playlist);
            var result = new java.util.ArrayList<String>();

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();

                if (line.equals(segmentName)) {
                    if (!result.isEmpty() && result.get(result.size() - 1).startsWith("#EXTINF")) {
                        result.remove(result.size() - 1);
                    }
                    continue;
                }

                if (!line.equals("#EXT-X-ENDLIST")) {
                    result.add(lines.get(i));
                }
            }

            Files.write(playlist, result);

        } catch (Exception e) {
            System.out.println("[HLS] Cannot update playlist: " + e.getMessage());
        }
    }

}
/*
package ru.homeserver.photoshare.homeserver.video;

import org.springframework.stereotype.Service;
import ru.homeserver.photoshare.homeserver.config.VideoPreviewProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class HlsConversionService {
    private final Map<String, Integer> progresses = new ConcurrentHashMap<>();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    private final VideoPreviewProperties props;
    private final VideoCacheKeyService cacheKeyService;

    private final Map<String, HlsStatus> statuses = new ConcurrentHashMap<>();
    public HlsConversionService(
            VideoPreviewProperties props,
            VideoCacheKeyService cacheKeyService
    ) {
        this.props = props;
        this.cacheKeyService = cacheKeyService;
    }

    public HlsConversionState prepareHls(Path sourceFile, String publicBaseUrl) {
        String key = cacheKeyService.cacheKey(sourceFile);

        Path folder = props.getHlsCacheDir().resolve(key);
        Path playlist = folder.resolve("playlist.m3u8");
        Path init = folder.resolve("init.mp4");
        */
/*if (Files.exists(playlist)) {
            statuses.put(key, HlsStatus.READY);*//*

        if (isHlsCompleted(playlist)) {
            statuses.put(key, HlsStatus.READY);
            progresses.put(key, 100);

            return new HlsConversionState(
                    HlsStatus.READY,
                    publicBaseUrl + "/" + key + "/playlist.m3u8",
                    "HLS already completed"
            );
        }

        HlsStatus current = statuses.get(key);
        if (current == HlsStatus.RUNNING) {
            return new HlsConversionState(
                    HlsStatus.RUNNING,
                    publicBaseUrl + "/" + key + "/playlist.m3u8",
                    "Conversion already running"
            );
        }

        statuses.put(key, HlsStatus.RUNNING);

        try {
            Files.createDirectories(folder);
            int savedProgress = calculateSavedProgress(sourceFile);
            progresses.put(key, savedProgress);
            startFfmpeg(sourceFile, folder, key);
        } catch (Exception e) {
            statuses.put(key, HlsStatus.FAILED);
            return new HlsConversionState(HlsStatus.FAILED, null, e.getMessage());
        }

        return new HlsConversionState(
                HlsStatus.RUNNING,
                publicBaseUrl + "/" + key + "/playlist.m3u8",
                "Conversion started"
        );
    }
    public HlsStatus getStatus(Path sourceFile) {
        String key = cacheKeyService.cacheKey(sourceFile);

        Path folder = props.getHlsCacheDir().resolve(key);
        Path playlist = folder.resolve("playlist.m3u8");
        Path init = folder.resolve("init.mp4");
        boolean playable =
                isStableFile(playlist, 100)
                        && isStableFile(init, 1024)
                        && hasAnySegment(folder);
        */
/*boolean playable = Files.exists(playlist)
                && Files.exists(init)
                && hasAnySegment(folder);*//*


        if (playable) {
            //progresses.put(key, Math.max(progresses.getOrDefault(key, 0), 15));
            return HlsStatus.READY;
        }

        return statuses.getOrDefault(key, HlsStatus.NOT_STARTED);
    }
    private boolean isFileReady(Path file, long minSizeBytes) {
        try {
            return Files.exists(file) && Files.size(file) >= minSizeBytes;
        } catch (Exception e) {
            return false;
        }
    }
    private boolean hasAnySegment(Path folder) {
        try (var stream = Files.list(folder)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".m4s"))
                    .filter(path -> isStableFile(path, 1024 * 100))
                    .count() >= 2;
        } catch (Exception e) {
            return false;
        }
        */
/*try (var stream = Files.list(folder)) {
            long count = stream
                    .filter(path -> path.getFileName().toString().endsWith(".m4s"))
                    .filter(path -> {
                        try {
                            return Files.size(path) > 1024 * 100;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .count();

            return count >= 2;
        } catch (Exception e) {
            return false;
        }*//*

    }
    private boolean isStableFile(Path file, long minSizeBytes) {
        try {
            if (!Files.exists(file)) return false;

            long size1 = Files.size(file);

            if (size1 < minSizeBytes) return false;

            Thread.sleep(300);

            long size2 = Files.size(file);

            return size1 == size2 && size2 >= minSizeBytes;
        } catch (Exception e) {
            return false;
        }
    }
    private int countSegments(Path folder) {
        try (var stream = Files.list(folder)) {
            return (int) stream
                    .filter(path -> path.getFileName().toString().endsWith(".m4s"))
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }
    private boolean isHlsCompleted(Path playlist) {
        try {
            if (!Files.exists(playlist)) return false;

            return Files.readAllLines(playlist)
                    .stream()
                    .anyMatch(line -> line.trim().equals("#EXT-X-ENDLIST"));
        } catch (Exception e) {
            return false;
        }
    }
    private void startFfmpeg(Path sourceFile, Path folder, String key) throws IOException {
        Path playlist = folder.resolve("playlist.m3u8");
        double resumeSeconds = getConvertedSecondsFromPlaylist(playlist);
        int startNumber = countSegments(folder);

        double durationSeconds = getDurationSeconds(sourceFile);
        boolean resume = Files.exists(playlist) && countSegments(folder) > 0;
        ProcessBuilder pb = new ProcessBuilder(
                props.getFfmpegPath(),
                "-y",
                "-fflags", "+genpts",
                //"-ss", String.valueOf(resumeSeconds),

                "-i", sourceFile.toAbsolutePath().toString(),

               */
/* "-map", "0:1",
                "-map", "0:a?",
                "-dn",
                "-sn",*//*


                "-vf", "scale=" + props.getPreviewWidth() + ":-2",

                "-c:v", "h264_nvenc",
                "-preset", "p4",
                "-b:v", props.getPreviewVideoBitrate(),
                "-maxrate", props.getPreviewVideoBitrate(),
                "-bufsize", "10000k",
                "-pix_fmt", "yuv420p",

                "-g", "60",
                "-keyint_min", "60",
                "-sc_threshold", "0",
                "-force_key_frames", "expr:gte(t,n_forced*2)",

                "-c:a", "aac",
                "-b:a", props.getPreviewAudioBitrate(),

                "-avoid_negative_ts", "make_zero",
                "-reset_timestamps", "1",

                "-f", "hls",
                "-hls_time", String.valueOf(props.getHlsSegmentSeconds()),
                "-hls_playlist_type", "event", //"vod", //vod хорош после полной готовности
                "-hls_flags", resume ? "append_list" : "independent_segments",
                "-start_number", String.valueOf(resume ? startNumber : 0),
                */
/*"-hls_flags", "append_list",
                "-start_number", String.valueOf(startNumber),*//*


                "-hls_segment_type", "fmp4",
                "-hls_fmp4_init_filename", "init.mp4",
                "-hls_segment_filename", "seg_%06d.m4s",

                "playlist.m3u8"
        );

        pb.redirectErrorStream(true);
        pb.directory(folder.toFile());
        Process process = pb.start();

        processes.put(key, process);

        Thread.ofVirtual().start(() -> {
            try (var scanner = new java.util.Scanner(process.getInputStream())) {

                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();

                    System.out.println("[FFMPEG] " + line);

                    int percent = parseProgressPercent(line, durationSeconds, resumeSeconds);

                    if (percent >= 0) {
                        progresses.put(key, percent);
                    }
                }

                int exitCode = process.waitFor();

                System.out.println("[FFMPEG] EXIT = " + exitCode);

                Path readyInitFile = folder.resolve("init.mp4");
                Path readyPlaylist = folder.resolve("playlist.m3u8");

                if (exitCode == 0
                        && Files.exists(readyPlaylist)
                        && Files.size(readyPlaylist) > 0
                        && Files.exists(readyInitFile)
                        && Files.size(readyInitFile) > 0) {
                    statuses.put(key, HlsStatus.READY);
                    progresses.put(key, 100);
                } else {
                    System.out.println("[HLS] Not ready:");
                    System.out.println("[HLS] playlist exists = " + Files.exists(readyPlaylist));
                    System.out.println("[HLS] init exists = " + Files.exists(readyInitFile));
                    statuses.put(key, HlsStatus.FAILED);
                    progresses.put(key, -1);
                }

            } catch (Exception e) {
                e.printStackTrace();
                statuses.put(key, HlsStatus.FAILED);
                progresses.put(key, -1);
            } finally {
            processes.remove(key);
        }
        });
        */
/*Thread.ofVirtual().start(() -> {
            try (var input = process.getInputStream()) {
                input.transferTo(OutputStream.nullOutputStream());
                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    statuses.put(key, HlsStatus.READY);
                } else {
                    statuses.put(key, HlsStatus.FAILED);
                }
            } catch (Exception e) {
                statuses.put(key, HlsStatus.FAILED);
            }
        });*//*

    }
    public void cancel(Path sourceFile) {
        String key = cacheKeyService.cacheKey(sourceFile);

        Process process = processes.remove(key);

        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }

        statuses.put(key, HlsStatus.NOT_STARTED);

        int savedProgress = calculateSavedProgress(sourceFile);

        */
/*if (savedProgress > 0) {
            progresses.put(key, savedProgress);
        }*//*


        progresses.put(key, savedProgress);
    }
    public HlsProgressDto getProgress(Path sourceFile) {
        String key = cacheKeyService.cacheKey(sourceFile);
        Path playlist = props.getHlsCacheDir().resolve(key).resolve("playlist.m3u8");

        if (isHlsCompleted(playlist)) {
            return new HlsProgressDto(HlsStatus.READY, 100);
        }
        return new HlsProgressDto(
                statuses.getOrDefault(key, HlsStatus.NOT_STARTED),
                progresses.getOrDefault(key, calculateSavedProgress(sourceFile))
        );
        */
/*return new HlsProgressDto(
                getStatus(sourceFile),
                progresses.getOrDefault(key, calculateSavedProgress(sourceFile))
        );*//*

    }
    private int calculateSavedProgress(Path sourceFile) {
        String key = cacheKeyService.cacheKey(sourceFile);
        Path folder = props.getHlsCacheDir().resolve(key);
        Path playlist = folder.resolve("playlist.m3u8");

        if (!Files.exists(playlist)) {
            return 0;
        }

        double duration = getDurationSeconds(sourceFile);
        double converted = getConvertedSecondsFromPlaylist(playlist);

        if (duration <= 0 || converted <= 0) {
            return 0;
        }

        return Math.min(99, (int) Math.round(converted * 100.0 / duration));
    }
    private double getConvertedSecondsFromPlaylist(Path playlist) {
        try {
            double total = 0;

            for (String line : Files.readAllLines(playlist)) {
                if (line.startsWith("#EXTINF:")) {
                    String value = line
                            .substring("#EXTINF:".length())
                            .replace(",", "")
                            .trim();

                    total += Double.parseDouble(value);
                }
            }

            return total;
        } catch (Exception e) {
            return 0;
        }
    }
    private int parseProgressPercent(String line, double durationSeconds, double resumeSeconds) {
        if (durationSeconds <= 0) return 0;

        int timeIndex = line.indexOf("time=");
        if (timeIndex < 0) return -1;

        String timePart = line.substring(timeIndex + 5).trim().split("\\s+")[0];
        String[] parts = timePart.split(":");

        if (parts.length != 3) return -1;

        try {
            double hours = Double.parseDouble(parts[0]);
            double minutes = Double.parseDouble(parts[1]);
            double seconds = Double.parseDouble(parts[2]);

            double currentSeconds = hours * 3600 + minutes * 60 + seconds;
            double totalConverted = resumeSeconds + currentSeconds;

            return Math.min(99, (int) Math.round(totalConverted * 100.0 / durationSeconds));
        } catch (Exception e) {
            return -1;
        }
    }
    private double getDurationSeconds(Path sourceFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    props.getFfprobePath(),
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    sourceFile.toAbsolutePath().toString()
            );

            pb.redirectErrorStream(true);

            Process process = pb.start();

            String output;
            try (var input = process.getInputStream()) {
                output = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }

            int exitCode = process.waitFor();

            if (exitCode == 0 && !output.isBlank()) {
                return Double.parseDouble(output);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return 0;
    }
}*/
