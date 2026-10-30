package ru.homeserver.photoshare.homeserver.music;

import java.util.Set;

public record MusicRequest(String description, String lyrics, String model, String language,
                           int duration, Integer bpm, Long seed, int steps,
                           boolean instrumental, boolean thinking, boolean enhance,
                           String taskType, String sourceId, String changes, Double sourceStrength,
                           Double repaintStart, Double repaintEnd) {
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
        if (!Set.of("text2music", "cover", "repaint").contains(taskType))
            throw new IllegalArgumentException("Неизвестный режим генерации");
        if ("text2music".equals(taskType)) { sourceId = ""; repaintStart = null; repaintEnd = null; }
        if (!Double.isFinite(sourceStrength) || sourceStrength < 0 || sourceStrength > 1 || changes.length() > 4000)
            throw new IllegalArgumentException("Проверьте описание изменений и сходство с образцом (0–1)");
        if (!"text2music".equals(taskType)) {
            try { if (!java.util.UUID.fromString(sourceId).toString().equals(sourceId)) throw new IllegalArgumentException(); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Загрузите звуковой образец"); }
            if (changes.isBlank()) throw new IllegalArgumentException("Опишите, что изменить в образце");
        }
        if ("repaint".equals(taskType) && (repaintStart == null || repaintEnd == null
                || !Double.isFinite(repaintStart) || !Double.isFinite(repaintEnd) || repaintStart < 0
                || repaintEnd - repaintStart < 3 || repaintEnd - repaintStart > 90 || repaintEnd > 300))
            throw new IllegalArgumentException("Выберите фрагмент от 3 до 90 секунд в пределах образца");
        description = description == null ? "" : description.strip();
        if (description.isEmpty() && !"text2music".equals(taskType)) description = changes;
        lyrics = lyrics == null ? "" : lyrics.strip();
        if (description.isEmpty() || description.length() > 4000 || lyrics.length() > 16000)
            throw new IllegalArgumentException("Нужно описание до 4000 символов и текст до 16000 символов");
        if (!Set.of("acestep-v15-turbo", "acestep-v15-xl-turbo").contains(model == null ? "" : model))
            throw new IllegalArgumentException("Неизвестная музыкальная модель");
        if (!Set.of("ru", "en", "zh", "ja", "ko", "de", "fr", "es", "pt", "it").contains(language == null ? "" : language))
            throw new IllegalArgumentException("Выберите язык песни");
        if (duration < 10 || duration > 300 || steps < 1 || steps > 20
                || (bpm != null && (bpm < 30 || bpm > 300))
                || (seed != null && (seed < 0 || seed > Integer.MAX_VALUE)))
            throw new IllegalArgumentException("Проверьте длительность (10–300), шаги (1–20), BPM (30–300) и seed");
        if ("text2music".equals(taskType) && !instrumental && lyrics.isEmpty())
            throw new IllegalArgumentException("Введите текст песни или включите режим без вокала");
    }
}
