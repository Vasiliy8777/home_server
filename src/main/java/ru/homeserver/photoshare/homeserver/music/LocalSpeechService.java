package ru.homeserver.photoshare.homeserver.music;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

@Component
public class LocalSpeechService {
    private final MusicProperties properties;
    private final ObjectMapper json;
    public LocalSpeechService(MusicProperties properties, ObjectMapper json) { this.properties = properties; this.json = json; }

    public boolean installed() {
        return installed(false);
    }
    public boolean installed(boolean clone) {
        Path model = Path.of(clone ? properties.getSpeechCloneModel() : properties.getSpeechModel());
        return Files.isRegularFile(Path.of(properties.getSpeechPython()))
                && Files.isRegularFile(model.resolve("model.safetensors"))
                && Files.isRegularFile(model.resolve("speech_tokenizer/model.safetensors"))
                && Files.isRegularFile(Path.of(properties.getRoot()).resolve("runtime/generate_speech.py"));
    }

    public void generate(MusicRequest request, Path work) throws IOException, InterruptedException {
        generate(request, work, null);
    }
    public void generate(MusicRequest request, Path work, Path reference) throws IOException, InterruptedException {
        boolean clone = "speech_clone".equals(request.taskType());
        if (!installed(clone)) throw new IOException("Озвучка не установлена. Выполните scripts/setup-speech.ps1" + (clone ? " -IncludeClone" : ""));
        if (clone && (reference == null || !Files.isRegularFile(reference))) throw new IOException("Не найден образец голоса");
        Files.createDirectories(work);
        Path input = work.resolve("speech-request.json");
        json.writeValue(input.toFile(), request);
        Path root = Path.of(properties.getRoot()).toAbsolutePath().normalize();
        var args = new java.util.ArrayList<>(java.util.List.of(properties.getSpeechPython(), root.resolve("runtime/generate_speech.py").toString(),
                "--request", input.toString(), "--model", clone ? properties.getSpeechCloneModel() : properties.getSpeechModel(), "--output", work.resolve("result.part").toString()));
        if (clone) {
            Path copy = work.resolve("voice-reference.wav");
            Files.copy(reference, copy, StandardCopyOption.REPLACE_EXISTING);
            args.add("--reference"); args.add(copy.toString());
            Files.writeString(work.resolve("voice-reference-text.txt"), request.referenceText());
        }
        var builder = new ProcessBuilder(args);
        builder.directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("speech.log").toFile());
        var env = builder.environment();
        env.put("HF_HUB_OFFLINE", "1"); env.put("TRANSFORMERS_OFFLINE", "1");
        env.put("HF_HOME", root.resolve("cache/huggingface").toString());
        env.put("TEMP", root.resolve("tmp").toString()); env.put("TMP", root.resolve("tmp").toString());
        env.put("PYTHONIOENCODING", "utf-8"); env.put("PYTHONUNBUFFERED", "1");
        env.put("PYTORCH_CUDA_ALLOC_CONF", "backend:cudaMallocAsync");
        Process process = builder.start();
        try {
            if (!process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS))
                throw new IOException("Истекло время озвучки. Подробности: " + work.resolve("speech.log"));
            if (process.exitValue() != 0) {
                String log = Files.readString(work.resolve("speech.log"));
                if (log.contains("out of memory")) throw new IOException("Недостаточно видеопамяти для озвучки. Освободите GPU");
                throw new IOException("Не удалось озвучить текст. Подробности: " + work.resolve("speech.log"));
            }
            Path audio = work.resolve("result.part");
            if (!Files.isRegularFile(audio) || Files.size(audio) <= 44) throw new IOException("Модель озвучки вернула пустой файл");
            Files.move(audio, work.resolve("result.wav"), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }
}
