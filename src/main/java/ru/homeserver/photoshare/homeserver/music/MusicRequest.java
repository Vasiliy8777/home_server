package ru.homeserver.photoshare.homeserver.music;

import java.util.Set;

public record MusicRequest(String description, String lyrics, String model, String language,
                           int duration, Integer bpm, Long seed, int steps,
                           boolean instrumental, boolean thinking, boolean enhance,
                           String taskType, String sourceId, String changes, Double sourceStrength,
                           Double repaintStart, Double repaintEnd,
                           String referenceText, boolean voiceOnly) {
    public MusicRequest(String description, String lyrics, String model, String language, int duration,
                        Integer bpm, Long seed, int steps, boolean instrumental, boolean thinking, boolean enhance,
                        String taskType, String sourceId, String changes, Double sourceStrength, Double repaintStart, Double repaintEnd) {
        this(description, lyrics, model, language, duration, bpm, seed, steps, instrumental, thinking, enhance,
                taskType, sourceId, changes, sourceStrength, repaintStart, repaintEnd, "", false);
    }
    public MusicRequest(String description, String lyrics, String model, String language, int duration,
                        Integer bpm, Long seed, int steps, boolean instrumental, boolean thinking, boolean enhance) {
        this(description, lyrics, model, language, duration, bpm, seed, steps, instrumental, thinking, enhance,
                "text2music", "", "", 0.7, null, null);
    }
    public MusicRequest {
        taskType = taskType == null || taskType.isBlank() ? "text2music" : taskType;
        sourceId = sourceId == null ? "" : sourceId;
        changes = changes == null ? "" : changes.strip();
        sourceStrength = sourceStrength == null ? 0.7 : sourceStrength;
        if (!Set.of("text2music", "cover", "repaint", "speech", "speech_clone").contains(taskType))
            throw new IllegalArgumentException("Неизвестный режим генерации");
        boolean clone = "speech_clone".equals(taskType);
        boolean speech = "speech".equals(taskType) || clone;
        referenceText = referenceText == null ? "" : referenceText.strip();
        if (referenceText.length() > 4000 || (clone && !voiceOnly && referenceText.isEmpty()))
            throw new IllegalArgumentException("Введите точный текст образца или включите режим без расшифровки");
        if (!clone) { referenceText = ""; voiceOnly = false; }
        if (speech) { model = clone ? "Qwen3-TTS-12Hz-1.7B-Base" : "Qwen3-TTS-12Hz-1.7B-VoiceDesign";
            instrumental = false; thinking = false; enhance = false;
            duration = 120; bpm = null; steps = 8; changes = ""; }
        if ("text2music".equals(taskType) || (speech && !clone)) sourceId = "";
        if ("text2music".equals(taskType) || speech) { repaintStart = null; repaintEnd = null; }
        if (!Double.isFinite(sourceStrength) || sourceStrength < 0 || sourceStrength > 1 || changes.length() > 4000)
            throw new IllegalArgumentException("Проверьте описание изменений и сходство с образцом (0–1)");
        if ((!"text2music".equals(taskType) && !speech) || clone) {
            try { if (!java.util.UUID.fromString(sourceId).toString().equals(sourceId)) throw new IllegalArgumentException(); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Загрузите звуковой образец"); }
            if (!clone && changes.isBlank()) throw new IllegalArgumentException("Опишите, что изменить в образце");
        }
        if ("repaint".equals(taskType) && (repaintStart == null || repaintEnd == null
                || !Double.isFinite(repaintStart) || !Double.isFinite(repaintEnd) || repaintStart < 0
                || repaintEnd - repaintStart < 3 || repaintEnd - repaintStart > 90 || repaintEnd > 300))
            throw new IllegalArgumentException("Выберите фрагмент от 3 до 90 секунд в пределах образца");
        description = description == null ? "" : description.strip();
        // Base voice cloning follows the sample, and has no instruction-control API.
        if (clone) description = "Озвучка по образцу голоса";
        if (description.isEmpty() && !"text2music".equals(taskType) && !speech) description = changes;
        lyrics = lyrics == null ? "" : lyrics.strip();
        if (description.isEmpty() || description.length() > 4000 || lyrics.length() > 16000)
            throw new IllegalArgumentException("Нужно описание до 4000 символов и текст до 16000 символов");
        if (!speech && !Set.of("acestep-v15-turbo", "acestep-v15-xl-turbo").contains(model == null ? "" : model))
            throw new IllegalArgumentException("Неизвестная музыкальная модель");
        if (!Set.of("ru", "en", "zh", "ja", "ko", "de", "fr", "es", "pt", "it").contains(language == null ? "" : language))
            throw new IllegalArgumentException("Выберите язык песни");
        // Installed Turbo checkpoints clamp values above 8 in the engine itself.
        // Keep accepting older saved requests (up to 20), recording their effective value.
        if (duration < 10 || duration > 300 || steps < 1 || steps > 20
                || (bpm != null && (bpm < 30 || bpm > 300))
                || (seed != null && (seed < 0 || seed > 4294967295L)))
            throw new IllegalArgumentException("Проверьте длительность (10–300), шаги (1–20), BPM (30–300) и seed");
        steps = Math.min(steps, 8);
        if (("text2music".equals(taskType) || speech) && !instrumental && lyrics.isEmpty())
            throw new IllegalArgumentException("Введите текст песни или включите режим без вокала");
    }

    public MusicRequest withSeed(long value) {
        return new MusicRequest(description, lyrics, model, language, duration, bpm, value, steps,
                instrumental, thinking, enhance, taskType, sourceId, changes, sourceStrength, repaintStart, repaintEnd, referenceText, voiceOnly);
    }
}
