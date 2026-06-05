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
        //boolean finished = hasPlaylist && playlistHasEndlist(playlist);
        /*boolean fullyConverted = isFullyConverted(sourceFile, playlist);
        boolean finished = hasPlaylist && (playlistHasEndlist(playlist) || fullyConverted);*/

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
                //rebuildPlaylist(folder, false);
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
    private boolean isFullyConverted(Path sourceFile, Path playlist) {
        double convertedSeconds = getConvertedSecondsFromPlaylist(playlist);
        double totalSeconds = getDurationSeconds(sourceFile);

        if (totalSeconds <= 0) {
            return false;
        }

        return convertedSeconds >= totalSeconds - props.getHlsSegmentSeconds();
    }
    public HlsStatus getStatus(Path sourceFile) {
        String key = cacheKeyService.cacheKey(sourceFile);

        Path folder = props.getHlsCacheDir().resolve(key);
        Path playlist = folder.resolve("playlist.m3u8");
        Path init = folder.resolve("init.mp4");

        boolean playable = Files.exists(playlist)
                && Files.exists(init)
                && hasAnySegment(folder);

        /*boolean finished = playable && (
                playlistHasEndlist(playlist)
                        || isFullyConverted(sourceFile, playlist)
        );*/
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
        /*long segmentCount = countSegments(folder);

        long startSeconds = Math.max(0, segmentCount * props.getHlsSegmentSeconds());*/
        long segmentCount = nextSegmentNumber(folder);
        double startSeconds = getConvertedSecondsFromPlaylist(folder.resolve("playlist.m3u8"));
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
    private long nextSegmentNumber(Path folder) {
        try (var stream = Files.list(folder)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".m4s"))
                    .map(p -> p.getFileName().toString())
                    .mapToLong(name -> {
                        try {
                            return Long.parseLong(
                                    name.replace("seg_", "").replace(".m4s", "")
                            );
                        } catch (Exception e) {
                            return -1;
                        }
                    })
                    .max()
                    .orElse(-1) + 1;
        } catch (Exception e) {
            return 0;
        }
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
        Path playlist = folder.resolve("playlist.m3u8");

        if (playlistHasEndlist(playlist)) {
            statuses.put(key, HlsStatus.READY);
            return;
        }

        String deletedSegment = deleteLastSegmentAndReturnName(folder);

        if (deletedSegment != null) {
            removeSegmentFromPlaylist(playlist, deletedSegment);
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
    private double getConvertedSecondsFromPlaylist(Path playlist) {
        try {
            if (!Files.exists(playlist)) return 0;

            return Files.readAllLines(playlist)
                    .stream()
                    .filter(line -> line.startsWith("#EXTINF:"))
                    .mapToDouble(line -> {
                        try {
                            String value = line
                                    .replace("#EXTINF:", "")
                                    .replace(",", "")
                                    .trim();

                            return Double.parseDouble(value);
                        } catch (Exception e) {
                            return 0;
                        }
                    })
                    .sum();

        } catch (Exception e) {
            return 0;
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

            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream())
            )) {
                output = reader.readLine();
            }

            process.waitFor();

            if (output == null || output.isBlank()) return 0;

            return Double.parseDouble(output.trim());

        } catch (Exception e) {
            return 0;
        }
    }
    public HlsProgressState getProgress(Path sourceFile) {
        String key = cacheKeyService.cacheKey(sourceFile);

        Path folder = props.getHlsCacheDir().resolve(key);
        Path playlist = folder.resolve("playlist.m3u8");

        double convertedSeconds = getConvertedSecondsFromPlaylist(playlist);
        if (convertedSeconds <= 0) {
            convertedSeconds = countSegments(folder) * props.getHlsSegmentSeconds();
        }
        double totalSeconds = getDurationSeconds(sourceFile);

        Process process = processes.get(key);
        boolean running = process != null && process.isAlive();

        boolean ready = Files.exists(playlist) && playlistHasEndlist(playlist);

        int progress = 0;

        if (totalSeconds > 0) {
            progress = (int) Math.min(100, Math.round((convertedSeconds / totalSeconds) * 100));
        }

        if (ready) {
            progress = 100;
        }
        if (progress >= 100 && !ready) {
            progress = 99;
        }

        return new HlsProgressState(
                progress,
                convertedSeconds,
                totalSeconds,
                running,
                ready
        );
    }

}
