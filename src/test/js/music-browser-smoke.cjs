// Real Chromium UI smoke test with a local fake backend and a valid WAV file.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const {spawn} = require('node:child_process');
const assert = require('node:assert/strict');
const project = path.resolve(__dirname, '../../..');
const dir = path.join(project, 'src/main/resources/static');
const browser = process.env.BROWSER_PATH || ['C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', '/usr/bin/chromium'].find(fs.existsSync);
if (!browser) throw new Error('Chromium is required');
const out = path.join(project, 'build/music-browser');
fs.mkdirSync(out, {recursive:true});
const index = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
const modal = index.match(/<dialog id="musicModal"[\s\S]*?<\/dialog>/)[0];
const app = fs.readFileSync(path.join(dir, 'app.js'), 'utf8');
const helpers = app.slice(0, app.indexOf('let parentPath'));
const checks = async function () {
    const el = id => document.getElementById(id);
    const results = [];
    const check = (name, ok) => { results.push({name, ok}); if (!ok) throw Error(name); };
    const until = async predicate => {
        for (let i=0;i<120;i++) { if (predicate()) return; await new Promise(r=>setTimeout(r,50)); }
        throw Error('timeout: ' + predicate);
    };
    try {
        el('musicOpen').click();
        await until(()=>el('musicConnection').textContent==='Генератор доступен');
        await until(()=>!el('musicWriteLyrics').disabled);
        check('modal opens', el('musicModal').open);
        await until(()=>el('musicProcessing').querySelector('img').naturalWidth>0);
        check('fits viewport', el('musicModal').getBoundingClientRect().width <= innerWidth);
        el('musicDescription').value='Тёплый инди-поп';
        el('musicLyrics').value='Напиши про море';
        el('musicWriteLyrics').click();
        check('lyrics generation displays loading1.gif', !el('musicProcessing').hidden && el('musicProcessing').querySelector('img').getAttribute('src')==='/img/loading1.gif');
        await until(()=>el('musicLyrics').value.includes('[Chorus]'));
        check('lyrics replace prompt', el('musicLyrics').value==='[Chorus]\nМоре зовёт');
        await until(()=>!el('musicStart').disabled);
        el('musicForm').requestSubmit();
        check('music generation displays processing indicator', !el('musicProcessing').hidden);
        await until(()=>!el('musicResult').hidden);
        await until(()=>el('musicAudio').duration>0 && el('musicTotal').textContent==='0:02');
        check('duration displayed', el('musicTotal').textContent==='0:02');
        check('seek enabled', !el('musicSeek').disabled);
        el('musicSeek').value=1;
        el('musicSeek').dispatchEvent(new Event('input'));
        check('seek changes audio time', el('musicAudio').currentTime>=.9);
        await until(()=>!el('musicSave').disabled);
        check('processing indicator clears after generation', el('musicProcessing').hidden);
        check('effective turbo limit is eight', el('musicSteps').max==='8');
        check('actual random seed is displayed', el('musicCurrentSeed').textContent.includes('123456'));
        el('musicUseSeed').click();
        check('current seed can be locked', el('musicSeedLocked').checked && el('musicSeed').value==='123456' && !el('musicSeed').disabled);
        check('temporary audio can be downloaded', !el('musicDownload').disabled);
        const nativeClick=HTMLAnchorElement.prototype.click;
        let download;
        HTMLAnchorElement.prototype.click=function(){ download={path:new URL(this.href).pathname,name:this.download}; };
        try { el('musicDownload').click(); } finally { HTMLAnchorElement.prototype.click=nativeClick; }
        check('download targets selected result', download?.path==='/api/music/jobs/2/download' && download.name==='song-2.wav');
        el('musicSave').click();
        await until(()=>el('musicStatus').textContent.startsWith('Сохранено'));
        check('saved result cannot be temporarily deleted', el('musicDelete').disabled);
        await until(()=>!el('musicDownload').disabled);
        check('saved audio can be downloaded', !el('musicDownload').disabled);
        await until(()=>!el('musicStart').disabled);
        el('musicForm').requestSubmit();
        await until(()=>!el('musicDelete').disabled);
        el('musicDelete').click();
        await until(()=>el('musicResult').hidden);
        check('temporary result removed', !el('musicAudio').getAttribute('src'));
        check('deleted audio cannot be downloaded', el('musicDownload').disabled);
        await until(()=>el('musicHistory').options.length>=4);
        check('history retains lyrics and both songs', el('musicHistory').options.length===4);
        await until(()=>!el('musicStart').disabled);
        el('musicMode').value='cover'; el('musicMode').dispatchEvent(new Event('change'));
        check('sample mode shows upload and locks duration', !el('musicSourceSection').hidden && el('musicDuration').disabled);
        const transfer=new DataTransfer();
        transfer.items.add(new File([await (await fetch('/sample.wav')).arrayBuffer()], 'sample.wav', {type:'audio/wav'}));
        el('musicSourceFile').files=transfer.files; el('musicSourceFile').dispatchEvent(new Event('change'));
        el('musicChanges').value='Добавить пианино'; el('musicLyrics').value='';
        el('musicForm').requestSubmit();
        check('upload displays processing indicator', !el('musicProcessing').hidden);
        await until(()=>el('musicStatus').textContent==='Готово' && !el('musicStart').disabled);
        check('sample metadata and preview restored', el('musicDuration').value==='30' && !el('musicSourceAudio').hidden);
        el('musicMode').value='repaint'; el('musicMode').dispatchEvent(new Event('change'));
        el('musicEditStart').value='2'; el('musicEditEnd').value='8'; el('musicChanges').value='Изменить только этот фрагмент';
        el('musicForm').requestSubmit();
        await until(()=>el('musicStatus').textContent==='Готово' && !el('musicStart').disabled);
        check('repaint completes and hides indicator', el('musicProcessing').hidden && !el('musicResult').hidden);
        el('musicMode').value='text2music'; el('musicMode').dispatchEvent(new Event('change'));
        el('musicDescription').value='force-failure'; el('musicLyrics').value='Текст песни';
        el('musicForm').requestSubmit();
        await until(()=>el('musicStatus').dataset.error==='true' && !el('musicStart').disabled);
        check('failed generation hides indicator and unlocks form', el('musicProcessing').hidden);
        el('musicClose').click();
        check('modal closes', !el('musicModal').open);
        el('musicOpen').click();
        await until(()=>!el('musicLoad').disabled);
        el('musicHistory').value='2'; el('musicLoad').click();
        await until(()=>!el('musicResult').hidden);
        check('saved audio reopens from history', el('musicSave').disabled && el('musicDelete').disabled);
        await until(()=>!el('musicStart').disabled);
        el('musicMode').value='speech'; el('musicMode').dispatchEvent(new Event('change'));
        check('speech hides music-only controls and sample upload', el('musicSourceSection').hidden && el('musicDuration').disabled && el('musicSteps').disabled && el('musicModel').disabled && el('musicWriteLyrics').disabled);
        el('musicDescription').value='Низкий спокойный голос';
        el('musicLyrics').value='Прочитай именно эти слова. Второе предложение!';
        el('musicForm').requestSubmit();
        check('speech shows loading indicator', !el('musicProcessing').hidden);
        await until(()=>el('musicStatus').textContent==='Готово' && !el('musicStart').disabled);
        check('speech audio can be played, saved and downloaded', !el('musicResult').hidden && !el('musicSave').disabled && !el('musicDownload').disabled);
        el('musicMode').value='speech_clone'; el('musicMode').dispatchEvent(new Event('change'));
        check('voice clone shows reference controls and hides unsupported description', !el('musicSourceSection').hidden && !el('musicVoiceSettings').hidden && el('musicDescription').hidden && el('musicReferenceText').required && !el('musicChanges').required);
        check('music sample is not reused as a voice sample', el('musicSourceFile').required && !el('musicSourceFile').files.length);
        const voiceTransfer=new DataTransfer();
        voiceTransfer.items.add(new File([await (await fetch('/sample.wav')).arrayBuffer()], 'voice.wav', {type:'audio/wav'}));
        el('musicSourceFile').files=voiceTransfer.files; el('musicSourceFile').dispatchEvent(new Event('change'));
        el('musicReferenceText').value='Точные слова в образце.';
        el('musicLyrics').value='Совершенно новый текст для озвучки.';
        el('musicForm').requestSubmit();
        await until(()=>el('musicStatus').textContent==='Готово' && !el('musicStart').disabled);
        check('voice cloning completes and supports download', !el('musicResult').hidden && !el('musicDownload').disabled && el('musicModel').value==='Qwen3-TTS-12Hz-1.7B-Base');
        const cloneId=el('musicAudio').getAttribute('src').match(/\/jobs\/([^/]+)\/audio/)[1];
        el('musicSave').click();
        await until(()=>el('musicStatus').textContent.startsWith('Сохранено') && !el('musicStart').disabled);
        el('musicMode').value='speech'; el('musicMode').dispatchEvent(new Event('change'));
        el('musicHistory').value=cloneId; el('musicLoad').click();
        await until(()=>el('musicMode').value==='speech_clone' && !el('musicStart').disabled);
        check('voice clone history restores sample and transcript', el('musicReferenceText').value==='Точные слова в образце.' && !el('musicSourceAudio').hidden && !el('musicVoiceOnly').checked);
        el('musicVoiceOnly').checked=true; el('musicVoiceOnly').dispatchEvent(new Event('change'));
        el('musicReferenceText').value='';
        check('voice-only mode does not require transcript', el('musicReferenceText').disabled && !el('musicReferenceText').required);
        el('musicForm').requestSubmit();
        await until(()=>el('musicStatus').textContent==='Готово' && !el('musicStart').disabled);
        check('voice-only cloning finishes', !el('musicResult').hidden && !el('musicSave').disabled);
    } catch(error) { results.push({name:String(error),ok:false}); }
    const marker=document.createElement('pre'); marker.id='smoke-results'; marker.textContent=JSON.stringify(results); document.body.append(marker);
};
let jobs = [];
let calls = [];
const source={id:'11111111-1111-4111-8111-111111111111',name:'sample.wav',duration:30,audioUrl:'/api/music/sources/11111111-1111-4111-8111-111111111111/audio'};
const voiceSource={id:'22222222-2222-4222-8222-222222222222',name:'voice.wav',duration:8,purpose:'voice',audioUrl:'/api/music/sources/22222222-2222-4222-8222-222222222222/audio'};
const wav=Buffer.alloc(44+32000);
wav.write('RIFF'); wav.writeUInt32LE(wav.length-8,4); wav.write('WAVEfmt ',8);
wav.writeUInt32LE(16,16); wav.writeUInt16LE(1,20); wav.writeUInt16LE(1,22);
wav.writeUInt32LE(8000,24); wav.writeUInt32LE(16000,28); wav.writeUInt16LE(2,32); wav.writeUInt16LE(16,34);
wav.write('data',36); wav.writeUInt32LE(32000,40);
const server=http.createServer(async(req,res)=>{
    if(req.url==='/sample.wav') { res.setHeader('Content-Type','audio/wav'); res.end(wav); return; }
    if (req.url==='/') {
        res.setHeader('Set-Cookie','XSRF-TOKEN=smoke-token; Path=/');
        res.setHeader('Content-Type','text/html; charset=utf-8');
        res.end(`<html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/style.css"><link rel="stylesheet" href="/music.css"><body><header class="topbar"><button id="musicOpen" class="music-glow">muz_gen</button></header>${modal}<script>${helpers}</script><script src="/music.js"></script><script>(${checks.toString()})()</script></body></html>`);
        return;
    }
    const file=['/style.css','/music.css','/music.js','/img/loading1.gif'].includes(req.url) && path.join(dir,req.url.slice(1));
    if(file) { res.setHeader('Content-Type',req.url.endsWith('.js')?'application/javascript':req.url.endsWith('.gif')?'image/gif':'text/css'); res.end(fs.readFileSync(file)); return; }
    if (!req.url.startsWith('/api/music')) { res.writeHead(404).end(); return; }
    const chunks=[]; for await (const chunk of req) chunks.push(chunk);
    const binary=req.url.startsWith('/api/music/sources?') && req.method==='POST';
    const body=binary?{bytes:Buffer.concat(chunks).length}:chunks.length?JSON.parse(Buffer.concat(chunks)):null;
    calls.push({url:req.url,method:req.method,csrf:req.headers['x-xsrf-token'],body});
    let data;
    const id=req.url.split('/')[4];
    const job=jobs.find(j=>j.id===id);
    if(req.url.endsWith('/audio')) {
        res.setHeader('Content-Type','audio/wav'); res.setHeader('Accept-Ranges','bytes');
        const range=req.headers.range?.match(/bytes=(\d+)-(\d*)/);
        if(range) {
            const start=Number(range[1]); const end=range[2]?Math.min(Number(range[2]),wav.length-1):wav.length-1;
            res.writeHead(206,{'Content-Range':`bytes ${start}-${end}/${wav.length}`,'Content-Length':end-start+1});
            res.end(wav.subarray(start,end+1));
        } else res.end(wav);
        return;
    }
    if(req.url.startsWith('/api/music/sources')) data=req.url.includes('purpose=voice') || req.url.includes(voiceSource.id) ? voiceSource : source;
    else if(req.url==='/api/music/status') data={available:true,busy:false,speechInstalled:true,speechCloneInstalled:true};
    else if(req.url==='/api/music/jobs' && req.method==='GET') data=jobs;
    else if(req.url==='/api/music/lyrics' || (req.url==='/api/music/jobs' && req.method==='POST')) {
        const lyrics=req.url.endsWith('/lyrics');
        if (!lyrics && body.seed==null) body.seed=123456;
        data={id:String(jobs.length+1),kind:lyrics?'lyrics':'music',state:'queued',message:'Подготовка',createdAt:new Date().toISOString(),request:lyrics?null:body};
        const failed=body?.description==='force-failure';
        jobs.unshift({...data,state:failed?'failed':'ready',message:failed?'Тестовая ошибка':'Готово',lyrics:lyrics?'[Chorus]\nМоре зовёт':'',pendingReads:1});
    } else if(job && req.url.endsWith('/save')) {job.state='saved';job.message='Сохранено: song.wav'; data=job;}
    else if(job && req.method==='DELETE') {job.state='deleted';job.message='Удалено';data=job;}
    else if(job?.pendingReads>0) { job.pendingReads--; data={...job,state:'running',message:'В процессе'}; }
    else data=job;
    res.setHeader('Content-Type','application/json'); res.end(JSON.stringify(data??{}));
});
(async()=>{
    await new Promise(r=>server.listen(0,'127.0.0.1',r));
    try {
        for(const width of [1280,390]) {
            jobs=[];calls=[];
            const args=['--headless','--disable-gpu','--no-sandbox','--autoplay-policy=no-user-gesture-required',
                `--user-data-dir=${path.join(out,'profile-'+width)}`,`--window-size=${width},1000`, `--screenshot=${path.join(out,'music-'+width+'.png')}`,'--virtual-time-budget=35000','--dump-dom',`http://127.0.0.1:${server.address().port}/`];
            const child=spawn(browser,args,{windowsHide:true}); let output=''; let error='';
            child.stdout.on('data',d=>output+=d);child.stderr.on('data',d=>error+=d);
            const deadline=setTimeout(()=>child.kill(),45000);
            await new Promise((resolve,reject)=>{child.on('error',reject);child.on('close',resolve);});clearTimeout(deadline);
            fs.writeFileSync(path.join(out,`result-${width}.html`),output);
            const marker=output.match(/<pre id="smoke-results">([\s\S]*?)<\/pre>/);
            assert.ok(marker, 'Missing UI test results: '+error.slice(-500));
            const results=JSON.parse(marker[1].replaceAll('&gt;','>').replaceAll('&lt;','<').replaceAll('&amp;','&'));
            assert.ok(results.length>=10 && results.every(r=>r.ok),JSON.stringify(results));
            assert.ok(calls.filter(c=>c.method!=='GET').every(c=>c.csrf==='smoke-token'),'Missing CSRF token');
            const music=calls.find(c=>c.url==='/api/music/jobs' && c.method==='POST').body;
            assert.equal(music.model,'acestep-v15-xl-turbo');assert.equal(music.duration,120);
            const allMusic=calls.filter(c=>c.url==='/api/music/jobs' && c.method==='POST');
            assert.equal(allMusic[1].body.seed,123456);
            const edits=allMusic.filter(c=>['cover','repaint'].includes(c.body.taskType)).map(c=>c.body);
            assert.equal(edits.length,2);
            assert.equal(edits[0].taskType,'cover'); assert.equal(edits[0].sourceId,source.id); assert.equal(edits[0].thinking,false);
            assert.equal(edits[1].taskType,'repaint'); assert.equal(edits[1].repaintStart,2); assert.equal(edits[1].repaintEnd,8);
            const speech=allMusic.find(c=>c.body.taskType==='speech').body;
            assert.equal(speech.lyrics,'Прочитай именно эти слова. Второе предложение!');
            assert.equal(speech.sourceId,''); assert.equal(speech.thinking,false); assert.equal(speech.instrumental,false);
            const clones=allMusic.filter(c=>c.body.taskType==='speech_clone').map(c=>c.body);
            assert.equal(clones.length,2); assert.equal(clones[0].sourceId,voiceSource.id);
            assert.equal(clones[0].referenceText,'Точные слова в образце.'); assert.equal(clones[0].voiceOnly,false);
            assert.equal(clones[0].lyrics,'Совершенно новый текст для озвучки.');
            assert.equal(clones[1].voiceOnly,true); assert.equal(clones[1].referenceText,'');
            const uploads=calls.filter(c=>c.url.startsWith('/api/music/sources?'));
            assert.equal(uploads.length,2); assert.equal(uploads[0].body.bytes,wav.length);
            assert.ok(uploads[1].url.includes('purpose=voice'));
            console.log(`Music UI ${width}px: ${results.length} checks passed, CSRF verified`);
        }
    } finally { server.close(); }
})().catch(e=>{console.error(e);process.exitCode=1;});
