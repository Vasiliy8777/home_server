(() => {
    'use strict';
    const el = id => document.getElementById(id);
    const modal = el('musicModal');
    if (!modal || window.PUBLIC_SHARE_MODE === true) return;
    const audio = el('musicAudio');
    let active = null;
    let result = null;
    let timer = null;
    let actionBusy = false;
    let pendingLabel = '';
    let selectedSource = null;
    let sourcePreviewUrl = null;
    let jobs = [];
    let currentSeed = null;
    let musicModel = 'acestep-v15-xl-turbo';
    let serviceStatus = null;

    function rememberSeed(job) {
        if (job?.request?.seed == null) return;
        currentSeed = job.request.seed;
        el('musicCurrentSeed').textContent = `Текущий seed: ${currentSeed}`;
        if (!el('musicSeedLocked').checked) el('musicSeed').value = currentSeed;
    }

    const running = job => job && ['queued', 'running'].includes(job.state);
    const time = seconds => {
        const n = Number.isFinite(seconds) ? Math.max(0, Math.floor(seconds)) : 0;
        return `${Math.floor(n / 60)}:${String(n % 60).padStart(2, '0')}`;
    };
    const message = (text, error = false) => {
        el('musicStatus').textContent = text;
        el('musicStatus').dataset.error = String(error);
    };

    async function api(path, method = 'GET', body) {
        const response = await secureFetch('/api/music' + path, {
            method, ...(body === undefined ? {} : body instanceof Blob
                ? {headers: {'Content-Type': 'application/octet-stream'}, body}
                : {headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)})
        });
        if (response.redirected || response.status === 401 || response.status === 403) throw new Error('Обновите страницу и войдите в облако');
        let data;
        try {
            data = await response.json();
        } catch {
            throw new Error('Сервер вернул неожиданный ответ');
        }
        if (!response.ok) throw new Error(data.error || 'Ошибка музыкального сервиса');
        return data;
    }

    function controls() {
        const busy = actionBusy || running(active);
        const clone = el('musicMode').value === 'speech_clone';
        const speech = el('musicMode').value === 'speech' || clone;
        const voiceModel = clone ? 'Qwen3-TTS-12Hz-1.7B-Base' : 'Qwen3-TTS-12Hz-1.7B-VoiceDesign';
        const speechModels = ['Qwen3-TTS-12Hz-1.7B-Base', 'Qwen3-TTS-12Hz-1.7B-VoiceDesign'];
        if (el('musicModel').value && !speechModels.includes(el('musicModel').value)) musicModel = el('musicModel').value;
        for (const model of speechModels) el('musicModel').querySelector(`option[value="${model}"]`).hidden = !speech || model !== voiceModel;
        if (speech) el('musicModel').value = voiceModel;
        else if (speechModels.includes(el('musicModel').value)) el('musicModel').value = musicModel;
        if (serviceStatus) el('musicConnection').textContent = speech
            ? ((clone ? serviceStatus.speechCloneInstalled : serviceStatus.speechInstalled) ? 'Озвучка доступна' : 'Модель озвучки не установлена')
            : serviceStatus.available ? 'Генератор доступен' : serviceStatus.autoStart ? 'Генератор запустится после start' : 'Генератор не запущен';
        const editing = ['cover', 'repaint'].includes(el('musicMode').value);
        const repaint = el('musicMode').value === 'repaint';
        el('musicSpeechHint').hidden = !speech || clone;
        el('musicVoiceSettings').hidden = !clone;
        el('musicSourceGuidance').hidden = clone;
        el('musicSourceLabel').textContent = clone ? 'Образец голоса — 3–30 секунд' : 'Звуковой образец';
        el('musicDescriptionLabel').hidden = clone;
        el('musicDescription').hidden = clone;
        el('musicDescription').disabled = busy || clone;
        el('musicChangesLabel').hidden = clone;
        el('musicChanges').hidden = clone;
        el('musicVoiceOnly').disabled = busy || !clone;
        el('musicReferenceText').disabled = busy || !clone || el('musicVoiceOnly').checked;
        el('musicReferenceText').required = clone && !el('musicVoiceOnly').checked;
        el('musicDescriptionLabel').textContent = speech ? 'Описание голоса и манеры речи' : 'Описание песни';
        el('musicLyricsLabel').textContent = speech ? 'Текст для озвучки — будет произнесён как введён' : 'Текст песни или запрос для его создания';
        el('musicDescription').placeholder = speech ? 'Например: тёплый низкий мужской голос, спокойная уверенная речь, чёткая дикция, без пения' : 'Жанр, настроение, инструменты и голос';
        el('musicLyrics').placeholder = speech ? 'Введите текст для произнесения. Пунктуация задаёт паузы; названия музыкальных секций не нужны.' : 'Введите готовый текст или пожелания и нажмите «Сгенерировать текст»';
        el('musicModel').disabled = busy || speech;
        el('musicBpm').disabled = busy || speech;
        el('musicSteps').disabled = busy || speech;
        el('musicInstrumental').disabled = busy || speech;
        el('musicSeed').disabled = busy || !el('musicSeedLocked').checked;
        el('musicSeed').required = el('musicSeedLocked').checked;
        el('musicSeedLocked').disabled = busy;
        el('musicUseSeed').disabled = busy || currentSeed == null;
        const needsSource = editing || clone;
        el('musicSourceSection').hidden = !needsSource;
        el('musicCoverSettings').hidden = repaint || clone;
        el('musicRepaintSettings').hidden = !repaint;
        el('musicMode').disabled = busy;
        el('musicSourceFile').disabled = busy;
        el('musicSourceFile').required = needsSource && !selectedSource;
        el('musicChanges').disabled = busy;
        el('musicChanges').required = editing;
        el('musicDescription').required = !editing && !clone;
        el('musicDuration').disabled = busy || editing || speech;
        el('musicThinking').disabled = busy || editing || speech;
        el('musicEnhance').disabled = busy || editing || speech;
        el('musicEditStart').disabled = busy || !repaint;
        el('musicEditEnd').disabled = busy || !repaint;
        el('musicEditStart').required = repaint;
        el('musicEditEnd').required = repaint;
        el('musicStrength').disabled = busy || repaint;
        const processing = Boolean(pendingLabel) || running(active);
        el('musicProcessing').hidden = !processing;
        el('musicProcessingLabel').textContent = pendingLabel || (active?.kind === 'lyrics' ? 'Пишем текст песни…' : ['speech', 'speech_clone'].includes(active?.request?.taskType) ? 'Озвучиваем текст…' : 'Создаём песню…');
        el('musicForm').setAttribute('aria-busy', String(processing));
        el('musicStart').disabled = busy;
        el('musicWriteLyrics').disabled = busy || speech || el('musicInstrumental').checked;
        el('musicSave').disabled = busy || result?.state !== 'ready';
        el('musicDownload').disabled = actionBusy || result?.kind !== 'music' || !['ready', 'saved'].includes(result?.state);
        el('musicDelete').disabled = busy || result?.state !== 'ready';
        el('musicLoad').disabled = busy;
        el('musicLyrics').disabled = busy || (!speech && el('musicInstrumental').checked);
        el('musicLyrics').required = speech;
    }

    function resetPlayer() {
        audio.pause();
        audio.removeAttribute('src');
        audio.load();
        el('musicResult').hidden = true;
        el('musicPlay').disabled = true;
        el('musicPause').disabled = true;
        el('musicSeek').disabled = true;
        el('musicSeek').value = 0;
        el('musicSeek').max = 0;
        el('musicElapsed').textContent = '0:00';
        el('musicTotal').textContent = '0:00';
    }

    function showResult(job) {
        result = job;
        rememberSeed(job);
        if (job.kind !== 'music' || !['ready', 'saved'].includes(job.state)) {
            resetPlayer();
            controls();
            return;
        }
        const src = `/api/music/jobs/${encodeURIComponent(job.id)}/audio`;
        if (audio.getAttribute('src') !== src) {
            resetPlayer();
            audio.src = src;
            audio.load();
        }
        el('musicResult').hidden = false;
        controls();
    }

    async function history() {
        jobs = await api('/jobs');
        const selected = el('musicHistory').value;
        el('musicHistory').replaceChildren(new Option('Выберите результат', ''));
        const names = {
            ready: 'Готово',
            saved: 'Сохранено',
            failed: 'Ошибка',
            deleted: 'Удалено',
            queued: 'Ожидает',
            running: 'Генерация'
        };
        for (const job of jobs) {
            const date = new Date(job.createdAt).toLocaleString();
            const title = job.kind === 'lyrics' ? 'Текст песни' : job.request?.taskType === 'speech_clone'
                ? 'Голос из образца · ' + job.request.lyrics : job.request?.description || 'Песня';
            el('musicHistory').add(new Option(`${date} · ${names[job.state] || job.state} · ${title.slice(0, 65)}`, job.id));
        }
        el('musicHistory').value = selected;
    }

    async function poll() {
        clearTimeout(timer);
        if (!active || !running(active)) return;
        try {
            active = await api(`/jobs/${encodeURIComponent(active.id)}`);
            rememberSeed(active);
            message(active.message, active.state === 'failed');
            if (!running(active)) {
                if (active.kind === 'lyrics' && active.state === 'ready') el('musicLyrics').value = active.lyrics;
                if (active.kind === 'music') showResult(active);
                await history();
            }
        } catch (error) {
            message(`${error.message}. Повторная проверка через несколько секунд`, true);
        } finally {
            controls();
            if (running(active)) timer = setTimeout(poll, 3000);
        }
    }

    async function action(work, label = '') {
        if (actionBusy) return;
        actionBusy = true;
        pendingLabel = label;
        controls();
        try {
            await work();
        } catch (error) {
            message(error.message, true);
        } finally {
            actionBusy = false;
            pendingLabel = '';
            controls();
        }
    }

    function releasePreview() {
        if (sourcePreviewUrl) URL.revokeObjectURL(sourcePreviewUrl);
        sourcePreviewUrl = null;
    }

    function displaySource(source) {
        releasePreview();
        selectedSource = source;
        el('musicSourceInfo').textContent = `${source.name} · ${time(source.duration)}`;
        el('musicSourceAudio').src = source.audioUrl;
        el('musicSourceAudio').hidden = false;
        if (el('musicMode').value !== 'speech_clone') el('musicDuration').value = Math.ceil(source.duration);
        el('musicEditStart').max = Math.max(0, source.duration - 3);
        el('musicEditEnd').max = source.duration;
        if (Number(el('musicEditStart').value) > source.duration - 3) el('musicEditStart').value = 0;
        if (Number(el('musicEditEnd').value) > source.duration) el('musicEditEnd').value = source.duration;
    }

    function sourceHint() {
        return el('musicMode').value === 'speech_clone' ? 'Выберите запись голоса от 3 до 30 секунд, до 100 МБ' : 'Выберите аудио от 10 до 300 секунд, до 100 МБ';
    }

    el('musicMode').addEventListener('change', () => {
        const purpose = el('musicMode').value === 'speech_clone' ? 'voice' : 'music';
        if (selectedSource && (selectedSource.purpose || 'music') !== purpose) {
            selectedSource = null;
            releasePreview();
            el('musicSourceFile').value = '';
            el('musicSourceAudio').pause();
            el('musicSourceAudio').removeAttribute('src');
            el('musicSourceAudio').hidden = true;
        }
        if (!selectedSource && !el('musicSourceFile').files[0]) el('musicSourceInfo').textContent = sourceHint();
        controls();
    });
    el('musicVoiceOnly').addEventListener('change', controls);
    el('musicSeedLocked').addEventListener('change', controls);
    el('musicUseSeed').addEventListener('click', () => {
        if (currentSeed == null) return;
        el('musicSeed').value = currentSeed;
        el('musicSeedLocked').checked = true;
        controls();
    });
    el('musicStrength').addEventListener('input', () => {
        el('musicStrengthValue').textContent = `${Math.round(Number(el('musicStrength').value) * 100)}%`;
    });
    el('musicSourceFile').addEventListener('change', () => {
        releasePreview();
        selectedSource = null;
        el('musicSourceAudio').pause();
        el('musicSourceAudio').removeAttribute('src');
        el('musicSourceAudio').hidden = true;
        const file = el('musicSourceFile').files[0];
        if (file?.size > 100 * 1024 * 1024) {
            el('musicSourceFile').value = '';
            message('Размер образца — не более 100 МБ', true);
        } else if (file) {
            sourcePreviewUrl = URL.createObjectURL(file);
            el('musicSourceAudio').src = sourcePreviewUrl;
            el('musicSourceAudio').hidden = false;
            el('musicSourceInfo').textContent = `${file.name} · будет загружен при нажатии start`;
        } else el('musicSourceInfo').textContent = sourceHint();
        controls();
    });
    window.addEventListener('pagehide', releasePreview);
    el('musicOpen').addEventListener('click', () => {
        modal.showModal();
        action(async () => {
            const status = await api('/status');
            serviceStatus = status;
            el('musicConnection').textContent = status.available ? 'Генератор доступен'
                : status.autoStart ? 'Генератор запустится после start' : 'Генератор не запущен';
            await history();
            if (!running(active)) {
                const unfinished = jobs.find(running);
                if (unfinished) {
                    active = unfinished;
                    poll();
                }
            }
        });
    });
    el('musicClose').addEventListener('click', () => modal.close());
    modal.addEventListener('close', () => {
        audio.pause();
        el('musicSourceAudio').pause();
        el('musicOpen').focus();
    });
    el('musicInstrumental').addEventListener('change', controls);
    el('musicWriteLyrics').addEventListener('click', () => action(async () => {
        const prompt = el('musicLyrics').value.trim();
        if (!prompt) throw new Error('Введите пожелания к тексту в поле текста песни');
        active = await api('/lyrics', 'POST', {
            prompt,
            description: el('musicDescription').value.trim(),
            language: el('musicLanguage').value
        });
        message(active.message);
        poll();
    }, 'Пишем текст песни…'));
    el('musicForm').addEventListener('submit', event => {
        event.preventDefault();
        if (!el('musicForm').reportValidity()) return;
        action(async () => {
            const clone = el('musicMode').value === 'speech_clone';
            const speech = el('musicMode').value === 'speech' || clone;
            const editing = ['cover', 'repaint'].includes(el('musicMode').value);
            if (el('musicMode').value === 'repaint') {
                const start = Number(el('musicEditStart').value), end = Number(el('musicEditEnd').value);
                if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end - start < 3 || end - start > 90)
                    throw new Error('Выберите фрагмент от 3 до 90 секунд');
            }
            if ((editing || clone) && !selectedSource) {
                const file = el('musicSourceFile').files[0];
                if (!file) throw new Error('Выберите звуковой образец');
                if (file.size > 100 * 1024 * 1024) throw new Error('Размер образца — не более 100 МБ');
                pendingLabel = 'Загружаем и проверяем образец…';
                controls();
                displaySource(await api(`/sources?name=${encodeURIComponent(file.name)}&purpose=${clone ? 'voice' : 'music'}`, 'POST', file));
            }
            if (el('musicMode').value === 'repaint' && Number(el('musicEditEnd').value) > selectedSource.duration)
                throw new Error('Конец фрагмента выходит за пределы образца');
            const optionalNumber = id => el(id).value.trim() === '' ? null : Number(el(id).value);
            const request = {
                description: clone ? '' : el('musicDescription').value.trim(),
                lyrics: el('musicLyrics').value.trim(),
                model: el('musicModel').value,
                language: el('musicLanguage').value,
                duration: Number(el('musicDuration').value),
                bpm: speech ? null : optionalNumber('musicBpm'),
                seed: el('musicSeedLocked').checked ? optionalNumber('musicSeed') : null,
                steps: Number(el('musicSteps').value),
                instrumental: !speech && el('musicInstrumental').checked,
                thinking: !speech && !editing && el('musicThinking').checked,
                enhance: !speech && !editing && el('musicEnhance').checked,
                taskType: el('musicMode').value,
                sourceId: editing || clone ? selectedSource.id : '',
                changes: editing ? el('musicChanges').value.trim() : '',
                sourceStrength: Number(el('musicStrength').value),
                repaintStart: el('musicMode').value === 'repaint' ? optionalNumber('musicEditStart') : null,
                repaintEnd: el('musicMode').value === 'repaint' ? optionalNumber('musicEditEnd') : null,
                referenceText: clone ? el('musicReferenceText').value.trim() : '',
                voiceOnly: clone && el('musicVoiceOnly').checked
            };
            pendingLabel = speech ? 'Озвучиваем текст…' : 'Создаём песню…';
            controls();
            active = await api('/jobs', 'POST', request);
            rememberSeed(active);
            message(active.message);
            poll();
        }, 'Подготавливаем генерацию…');
    });
    el('musicDownload').addEventListener('click', () => {
        if (!result || result.kind !== 'music' || !['ready', 'saved'].includes(result.state)) return;
        // Let the browser stream the download directly, including on mobile devices.
        const link = document.createElement('a');
        link.href = `/api/music/jobs/${encodeURIComponent(result.id)}/download`;
        link.download = `song-${result.id}.wav`;
        document.body.append(link);
        link.click();
        link.remove();
    });
    el('musicSave').addEventListener('click', () => action(async () => {
        if (!result) return;
        result = await api(`/jobs/${result.id}/save`, 'POST');
        message(result.message);
        controls();
        await history();
    }));
    el('musicDelete').addEventListener('click', () => action(async () => {
        if (!result) return;
        audio.pause();
        result = await api(`/jobs/${result.id}`, 'DELETE');
        message(result.message);
        showResult(result);
        await history();
    }));
    el('musicRefresh').addEventListener('click', () => action(history));
    el('musicLoad').addEventListener('click', () => action(async () => {
        const id = el('musicHistory').value;
        if (!id) return;
        const job = await api(`/jobs/${encodeURIComponent(id)}`);
        if (job.kind === 'lyrics' && job.state === 'ready') el('musicLyrics').value = job.lyrics;
        if (job.request) {
            const r = job.request;
            for (const [suffix, value] of Object.entries({
                Description: r.description,
                Lyrics: r.lyrics,
                Model: r.model,
                Language: r.language,
                Duration: r.duration,
                Bpm: r.bpm ?? '',
                Seed: r.seed ?? '',
                Steps: Math.min(r.steps, 8)
            })) el('music' + suffix).value = value;
            el('musicSeedLocked').checked = r.seed != null;
            el('musicInstrumental').checked = r.instrumental;
            el('musicThinking').checked = r.thinking;
            el('musicEnhance').checked = r.enhance;
            el('musicMode').value = r.taskType || 'text2music';
            el('musicReferenceText').value = r.referenceText || '';
            el('musicVoiceOnly').checked = r.voiceOnly === true;
            el('musicChanges').value = r.changes || '';
            el('musicStrength').value = r.sourceStrength ?? 0.7;
            el('musicStrengthValue').textContent = `${Math.round(Number(el('musicStrength').value) * 100)}%`;
            el('musicEditStart').value = r.repaintStart ?? 0;
            el('musicEditEnd').value = r.repaintEnd ?? 10;
            selectedSource = null;
            el('musicSourceFile').value = '';
            if (r.sourceId) displaySource(await api(`/sources/${encodeURIComponent(r.sourceId)}`));
            else {
                releasePreview();
                el('musicSourceAudio').pause();
                el('musicSourceAudio').removeAttribute('src');
                el('musicSourceAudio').hidden = true;
                el('musicSourceInfo').textContent = sourceHint();
            }
            showResult(job);
        }
        message(job.message, job.state === 'failed');
        if (running(job)) {
            active = job;
            poll();
        }
    }));
    el('musicPlay').addEventListener('click', () => {
        audio.play().catch(error => message(error.message, true));
    });
    el('musicPause').addEventListener('click', () => audio.pause());
    audio.addEventListener('loadedmetadata', () => {
        const valid = Number.isFinite(audio.duration) && audio.duration > 0;
        el('musicSeek').max = valid ? audio.duration : 0;
        el('musicTotal').textContent = time(audio.duration);
        el('musicSeek').disabled = !valid;
        el('musicPlay').disabled = !valid;
    });
    audio.addEventListener('timeupdate', () => {
        el('musicSeek').value = audio.currentTime;
        el('musicElapsed').textContent = time(audio.currentTime);
    });
    const playback = () => {
        el('musicPlay').disabled = !audio.paused;
        el('musicPause').disabled = audio.paused;
    };
    for (const event of ['play', 'pause', 'ended']) audio.addEventListener(event, playback);
    audio.addEventListener('error', () => {
        if (audio.getAttribute('src')) message('Не удалось загрузить аудио. Откройте результат повторно из истории', true);
    });
    el('musicSeek').addEventListener('input', () => {
        audio.currentTime = Number(el('musicSeek').value);
    });
    controls();
})();
