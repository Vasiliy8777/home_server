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
        try { data = await response.json(); } catch { throw new Error('Сервер вернул неожиданный ответ'); }
        if (!response.ok) throw new Error(data.error || 'Ошибка музыкального сервиса');
        return data;
    }
    function controls() {
        const busy = actionBusy || running(active);
        const editing = el('musicMode').value !== 'text2music';
        const repaint = el('musicMode').value === 'repaint';
        el('musicSourceSection').hidden = !editing;
        el('musicCoverSettings').hidden = repaint;
        el('musicRepaintSettings').hidden = !repaint;
        el('musicMode').disabled = busy;
        el('musicSourceFile').disabled = busy;
        el('musicSourceFile').required = editing && !selectedSource;
        el('musicChanges').disabled = busy;
        el('musicChanges').required = editing;
        el('musicDescription').required = !editing;
        el('musicDuration').disabled = busy || editing;
        el('musicThinking').disabled = busy || editing;
        el('musicEnhance').disabled = busy || editing;
        el('musicEditStart').disabled = busy || !repaint;
        el('musicEditEnd').disabled = busy || !repaint;
        el('musicEditStart').required = repaint;
        el('musicEditEnd').required = repaint;
        el('musicStrength').disabled = busy || repaint;
        const processing = Boolean(pendingLabel) || running(active);
        el('musicProcessing').hidden = !processing;
        el('musicProcessingLabel').textContent = pendingLabel || (active?.kind === 'lyrics' ? 'Пишем текст песни…' : 'Создаём песню…');
        el('musicForm').setAttribute('aria-busy', String(processing));
        el('musicStart').disabled = busy;
        el('musicWriteLyrics').disabled = busy || el('musicInstrumental').checked;
        el('musicSave').disabled = busy || result?.state !== 'ready';
        el('musicDownload').disabled = actionBusy || result?.kind !== 'music' || !['ready', 'saved'].includes(result?.state);
        el('musicDelete').disabled = busy || result?.state !== 'ready';
        el('musicLoad').disabled = busy;
        el('musicLyrics').disabled = busy || el('musicInstrumental').checked;
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
        if (job.kind !== 'music' || !['ready', 'saved'].includes(job.state)) { resetPlayer(); controls(); return; }
        const src = `/api/music/jobs/${encodeURIComponent(job.id)}/audio`;
        if (audio.getAttribute('src') !== src) { resetPlayer(); audio.src = src; audio.load(); }
        el('musicResult').hidden = false;
        controls();
    }
    async function history() {
        jobs = await api('/jobs');
        const selected = el('musicHistory').value;
        el('musicHistory').replaceChildren(new Option('Выберите результат', ''));
        const names = {ready: 'Готово', saved: 'Сохранено', failed: 'Ошибка', deleted: 'Удалено', queued: 'Ожидает', running: 'Генерация'};
        for (const job of jobs) {
            const date = new Date(job.createdAt).toLocaleString();
            const title = job.kind === 'lyrics' ? 'Текст песни' : job.request?.description || 'Песня';
            el('musicHistory').add(new Option(`${date} · ${names[job.state] || job.state} · ${title.slice(0, 65)}`, job.id));
        }
        el('musicHistory').value = selected;
    }
    async function poll() {
        clearTimeout(timer);
        if (!active || !running(active)) return;
        try {
            active = await api(`/jobs/${encodeURIComponent(active.id)}`);
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
        try { await work(); } catch (error) { message(error.message, true); }
        finally { actionBusy = false; pendingLabel = ''; controls(); }
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
        el('musicDuration').value = Math.ceil(source.duration);
        el('musicEditStart').max = Math.max(0, source.duration - 3);
        el('musicEditEnd').max = source.duration;
        if (Number(el('musicEditStart').value) > source.duration - 3) el('musicEditStart').value = 0;
        if (Number(el('musicEditEnd').value) > source.duration) el('musicEditEnd').value = source.duration;
    }
    el('musicMode').addEventListener('change', controls);
    el('musicStrength').addEventListener('input', () => { el('musicStrengthValue').textContent = `${Math.round(Number(el('musicStrength').value) * 100)}%`; });
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
        } else el('musicSourceInfo').textContent = 'Выберите аудио от 10 до 300 секунд, до 100 МБ';
        controls();
    });
    window.addEventListener('pagehide', releasePreview);
    el('musicOpen').addEventListener('click', () => {
        modal.showModal();
        action(async () => {
            const status = await api('/status');
            el('musicConnection').textContent = status.available ? 'Генератор доступен'
                : status.autoStart ? 'Генератор запустится после start' : 'Генератор не запущен';
            await history();
            if (!running(active)) {
                const unfinished = jobs.find(running);
                if (unfinished) { active = unfinished; poll(); }
            }
        });
    });
    el('musicClose').addEventListener('click', () => modal.close());
    modal.addEventListener('close', () => { audio.pause(); el('musicSourceAudio').pause(); el('musicOpen').focus(); });
    el('musicInstrumental').addEventListener('change', controls);
    el('musicWriteLyrics').addEventListener('click', () => action(async () => {
        const prompt = el('musicLyrics').value.trim();
        if (!prompt) throw new Error('Введите пожелания к тексту в поле текста песни');
        active = await api('/lyrics', 'POST', {prompt, description: el('musicDescription').value.trim(), language: el('musicLanguage').value});
        message(active.message);
        poll();
    }, 'Пишем текст песни…'));
    el('musicForm').addEventListener('submit', event => {
        event.preventDefault();
        if (!el('musicForm').reportValidity()) return;
        action(async () => {
            const editing = el('musicMode').value !== 'text2music';
            if (el('musicMode').value === 'repaint') {
                const start = Number(el('musicEditStart').value), end = Number(el('musicEditEnd').value);
                if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end - start < 3 || end - start > 90)
                    throw new Error('Выберите фрагмент от 3 до 90 секунд');
            }
            if (editing && !selectedSource) {
                const file = el('musicSourceFile').files[0];
                if (!file) throw new Error('Выберите звуковой образец');
                if (file.size > 100 * 1024 * 1024) throw new Error('Размер образца — не более 100 МБ');
                pendingLabel = 'Загружаем и проверяем образец…'; controls();
                displaySource(await api(`/sources?name=${encodeURIComponent(file.name)}`, 'POST', file));
            }
            if (el('musicMode').value === 'repaint' && Number(el('musicEditEnd').value) > selectedSource.duration)
                throw new Error('Конец фрагмента выходит за пределы образца');
            const optionalNumber = id => el(id).value.trim() === '' ? null : Number(el(id).value);
            const request = {
                description: el('musicDescription').value.trim(), lyrics: el('musicLyrics').value.trim(),
                model: el('musicModel').value, language: el('musicLanguage').value,
                duration: Number(el('musicDuration').value), bpm: optionalNumber('musicBpm'), seed: optionalNumber('musicSeed'),
                steps: Number(el('musicSteps').value), instrumental: el('musicInstrumental').checked,
                thinking: !editing && el('musicThinking').checked, enhance: !editing && el('musicEnhance').checked,
                taskType: el('musicMode').value, sourceId: editing ? selectedSource.id : '',
                changes: editing ? el('musicChanges').value.trim() : '', sourceStrength: Number(el('musicStrength').value),
                repaintStart: el('musicMode').value === 'repaint' ? optionalNumber('musicEditStart') : null,
                repaintEnd: el('musicMode').value === 'repaint' ? optionalNumber('musicEditEnd') : null
            };
            pendingLabel = 'Создаём песню…'; controls();
            active = await api('/jobs', 'POST', request);
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
        message(result.message); controls(); await history();
    }));
    el('musicDelete').addEventListener('click', () => action(async () => {
        if (!result) return;
        audio.pause();
        result = await api(`/jobs/${result.id}`, 'DELETE');
        message(result.message); showResult(result); await history();
    }));
    el('musicRefresh').addEventListener('click', () => action(history));
    el('musicLoad').addEventListener('click', () => action(async () => {
        const id = el('musicHistory').value;
        if (!id) return;
        const job = await api(`/jobs/${encodeURIComponent(id)}`);
        if (job.kind === 'lyrics' && job.state === 'ready') el('musicLyrics').value = job.lyrics;
        if (job.request) {
            const r = job.request;
            for (const [suffix, value] of Object.entries({Description:r.description, Lyrics:r.lyrics, Model:r.model,
                Language:r.language, Duration:r.duration, Bpm:r.bpm ?? '', Seed:r.seed ?? '', Steps:r.steps})) el('music' + suffix).value = value;
            el('musicInstrumental').checked = r.instrumental;
            el('musicThinking').checked = r.thinking;
            el('musicEnhance').checked = r.enhance;
            el('musicMode').value = r.taskType || 'text2music';
            el('musicChanges').value = r.changes || '';
            el('musicStrength').value = r.sourceStrength ?? 0.7;
            el('musicStrengthValue').textContent = `${Math.round(Number(el('musicStrength').value) * 100)}%`;
            el('musicEditStart').value = r.repaintStart ?? 0;
            el('musicEditEnd').value = r.repaintEnd ?? 10;
            selectedSource = null;
            el('musicSourceFile').value = '';
            if (r.sourceId) displaySource(await api(`/sources/${encodeURIComponent(r.sourceId)}`));
            else {
                releasePreview(); el('musicSourceAudio').pause();
                el('musicSourceAudio').removeAttribute('src'); el('musicSourceAudio').hidden = true;
                el('musicSourceInfo').textContent = 'Выберите аудио от 10 до 300 секунд, до 100 МБ';
            }
            showResult(job);
        }
        message(job.message, job.state === 'failed');
        if (running(job)) { active = job; poll(); }
    }));
    el('musicPlay').addEventListener('click', () => { audio.play().catch(error => message(error.message, true)); });
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
    const playback = () => { el('musicPlay').disabled = !audio.paused; el('musicPause').disabled = audio.paused; };
    for (const event of ['play', 'pause', 'ended']) audio.addEventListener(event, playback);
    audio.addEventListener('error', () => { if (audio.getAttribute('src')) message('Не удалось загрузить аудио. Откройте результат повторно из истории', true); });
    el('musicSeek').addEventListener('input', () => { audio.currentTime = Number(el('musicSeek').value); });
    controls();
})();
