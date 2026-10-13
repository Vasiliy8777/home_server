const test = require('node:test');
const assert = require('node:assert/strict');
const {harness, Element} = require('./browser-harness.cjs');

const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
const response = (data, ok = true) => ({ok, status: ok ? 200 : 500, json: async () => data});

test('private API includes CSRF on writes and public API maps upload/HLS endpoints', async () => {
    const h = harness();
    let request;
    h.window.fetch = async (url, options) => { request = {url, options}; return response({}); };
    await h.run('secureFetch("/api/files/folder", {method: "POST"})');
    assert.equal(request.options.headers['X-XSRF-TOKEN'], 'csrf token');
    assert.equal(request.options.credentials, 'same-origin');
    await h.run('secureFetch("/api/files/list")');
    assert.equal(request.options.headers['X-XSRF-TOKEN'], undefined);
    const publicApp = harness(true);
    assert.equal(publicApp.run('shareApi("/api/files/upload-chunk")'), '/share/public-token/upload-chunk');
    assert.equal(publicApp.run('shareApi("/api/video/hls/progress?path=a")'), '/share/public-token/video/hls/progress?path=a');
});

test('large/video downloads go directly to browser without fetch or false completion', async () => {
    const h = harness(true);
    h.run('globalThis.task = {id:"d", item:{name:"video.mp4",type:"video",downloadUrl:"/api/files/download?path=a",size:100000000}}; transferTasks.set(task.id,task)');
    await h.run('downloadResumableManaged(task)');
    const link = h.created.find(el => el.tag === 'a');
    assert.equal(link.href, '/share/public-token/download?path=a');
    assert.equal(link.clicked, true);
    assert.equal(h.run('task.status'), 'browser');
});

test('small download restarts from zero, uses one fetch and cancels/releases reader', async () => {
    const h = harness();
    h.storage.set('download_photo.jpg', '2');
    let fetches = 0, reads = 0, cancelled = false, released = false, options;
    h.window.fetch = async (_, opts) => {
        fetches++; options = opts;
        return {ok: true, status: 200, headers: {get: () => '3'}, body: {getReader: () => ({
            read: async () => reads++ ? {done: true} : {value: new Uint8Array([1, 2, 3]), done: false},
            cancel: async () => { cancelled = true; }, releaseLock: () => { released = true; }
        })}};
    };
    h.run('globalThis.task = {id:"d",item:{name:"photo.jpg",type:"image",downloadUrl:"/photo",size:3}}; transferTasks.set(task.id,task)');
    await h.run('downloadResumableManaged(task)');
    assert.equal(fetches, 1);
    assert.equal(options.headers.Range, undefined);
    assert.equal(h.created.filter(el => el.tag === 'a').length, 1);
    assert.equal(h.run('task.status'), 'done');
    assert.equal(cancelled && released, true);
});

test('download cancellation aborts a pending network request', async () => {
    const h = harness();
    h.window.fetch = (_, options) => new Promise((_, reject) => {
        options.signal.addEventListener('abort', () => reject(options.signal.reason), {once: true});
    });
    h.run('globalThis.task = {id:"d",item:{name:"a",downloadUrl:"/a",size:3}}; transferTasks.set(task.id,task)');
    const download = h.run('downloadResumableManaged(task)');
    h.run('removeTransferTask("d")');
    await assert.rejects(download, /Download cancelled/);
    assert.equal(h.created.filter(el => el.tag === 'a').length, 0);
});

test('image viewer has one pair of global drag listeners after repeated openings', () => {
    const h = harness();
    for (let i = 0; i < 10; i++) h.run('initViewerImageZoom()');
    assert.equal(h.window.listeners.get('mousemove').size, 1);
    assert.equal(h.window.listeners.get('mouseup').size, 1);
    h.run('cleanupViewerInput()');
    assert.equal(h.window.listeners.get('mousemove').size, 0);
    assert.equal(h.window.listeners.get('mouseup').size, 0);
});

test('pagination ignores old responses, including A to B to A transitions', async () => {
    const h = harness();
    let finish;
    h.window.fetch = () => new Promise(resolve => { finish = resolve; });
    h.run('folderLoadSession=1; resetFolderRequests(); currentPreparedJobId="old"; offset=0;');
    const pending = h.run('loadPreparedPage("old")');
    h.run('folderLoadSession=3; resetFolderRequests(); currentPreparedJobId="new"; loading=true;');
    finish(response({items: [{relativePath: 'stale'}], total: 1}));
    await pending;
    assert.equal(h.run('offset'), 0);
    assert.equal(h.run('currentItems.length'), 0);
    assert.equal(h.run('loading'), true);
});

test('page errors permit retry rather than marking folder fully loaded', async () => {
    const h = harness();
    h.window.fetch = async () => response({}, false);
    h.run('resetFolderRequests(); currentPreparedJobId="job";');
    await h.run('loadPreparedPage("job")');
    assert.equal(h.run('preparedAllLoaded'), false);
    assert.equal(h.run('loading'), false);
});

test('old metadata completion cannot decrement new session counters or apply stale values', async () => {
    const h = harness();
    let finish;
    h.window.fetch = () => new Promise(resolve => { finish = resolve; });
    h.run('enqueueMetadata(["old"]); globalThis.oldContext=metadataContext');
    assert.equal(h.run('oldContext.running'), 1);
    h.run('folderLoadSession++; resetFolderRequests()');
    finish(response({old: {createdAt: 100}}));
    await flush();
    assert.equal(h.run('metadataContext.running'), 0);
    assert.equal(h.run('oldContext.running'), 0);
    assert.equal(h.run('metadataCache.has("old")'), false);
});

test('metadata retries transient failures and respects concurrency limit', async () => {
    const h = harness();
    let calls = 0, active = 0, maximum = 0;
    h.window.fetch = async (_, options) => {
        calls++; active++; maximum = Math.max(maximum, active);
        await Promise.resolve(); active--;
        const paths = JSON.parse(options.body);
        return response(Object.fromEntries(paths.map(p => [p, {createdAt: 100}])), calls > 1);
    };
    h.run('enqueueMetadata(Array.from({length:400},(_,i)=>"p"+i))');
    await flush();
    assert.ok(maximum <= 4);
    assert.equal(h.run('metadataContext.running'), 0);
    assert.equal(h.run('metadataPending.size'), 0);
    assert.equal(h.run('metadataCache.size'), 400);
});

test('observers survive appended pages and disconnect on folder reset', () => {
    const h = harness();
    h.context.first = new Element();
    h.context.second = new Element();
    h.run('initLazyMetadata([first]); initLazyMetadata([second]); initLazyThumbs([first]); initLazyThumbs([second])');
    assert.equal(h.observers.length, 2);
    assert.equal(h.observers[0].targets.size, 2);
    assert.equal(h.observers[1].targets.size, 2);
    assert.equal(h.observers[0].options.root, null);
    h.run('resetFolderRequests()');
    assert.ok(h.observers.every(observer => observer.disconnected));
});

test('polling waits for slow requests and stop aborts pending request', async () => {
    const h = harness();
    let finish, calls = 0;
    h.context.callback = () => { calls++; return new Promise(resolve => { finish = resolve; }); };
    h.run('globalThis.poll = startSequentialPolling(callback,1000)');
    const tick = h.tick();
    assert.equal(calls, 1);
    assert.equal(h.timers.size, 0);
    h.run('stopPolling(poll)');
    finish(); await tick;
    assert.equal(h.timers.size, 0);
    assert.equal(h.run('poll.controller.signal.aborted'), true);
});

test('preview polling rejects failures and cancellation settles wait promise', async () => {
    const h = harness();
    h.window.fetch = async () => response({progress: -1});
    await assert.rejects(h.run('waitForPreview("id",500)'), /Preview creation failed/);
    h.window.fetch = async () => response({ready: false});
    const pending = h.run('waitForPreview("id",500)');
    await flush();
    h.run('previewWaitController.abort()');
    await assert.rejects(pending, {name: 'AbortError'});
    assert.equal(h.timers.size, 0);
});

test('progress updates coalesce DOM work and storage writes', () => {
    const h = harness();
    h.run('globalThis.task={id:"a"}; transferTasks.set("a",task); for(let i=0;i<20;i++)queueTransferProgress(task)');
    assert.equal(h.frames.size, 1);
    assert.equal(h.timers.size, 1);
});

test('successful pagination appends one page and advances offset once', async () => {
    const h = harness();
    const items = [{relativePath: 'a', name: 'a', type: 'file', size: 1}];
    h.window.fetch = async () => response({items, total: 1});
    h.context.createCard = item => { const el = new Element(); el.dataset.path = item.relativePath; return el; };
    h.run('resetFolderRequests(); currentPreparedJobId="job"');
    await h.run('loadPreparedPage("job")');
    assert.equal(h.run('offset'), 1);
    assert.equal(h.run('currentItems.length'), 1);
    assert.equal(h.run('cardByPath.size'), 1);
    assert.equal(h.run('preparedAllLoaded'), true);
    assert.equal(h.run('loading'), false);
});

test('upload sends only missing chunks, then finalizes once', async () => {
    const h = harness();
    const sent = [];
    let completed = 0;
    h.context.getUploadStatus = async () => ({totalChunks: 3, uploadedChunks: [0, 2]});
    h.context.sendChunk = async form => { sent.push(form.get('chunkIndex')); };
    h.context.completeUploadSession = async () => { completed++; };
    h.context.file = new Blob(['abc']);
    h.run('globalThis.task={id:"upload",name:"a",file,uploadId:"id",targetPath:"",uploadedChunks:[],status:"uploading"}; transferTasks.set(task.id,task)');
    await h.run('uploadFileResumableManaged(task,()=>false)');
    assert.deepEqual(sent, ['1']);
    assert.equal(completed, 1);
    assert.equal(h.run('task.status'), 'done');
});

test('paused and cancelled uploads never finalize', async () => {
    const h = harness();
    let completed = 0;
    h.context.getUploadStatus = async () => ({totalChunks: 1, uploadedChunks: [0]});
    h.context.completeUploadSession = async () => { completed++; };
    h.context.file = new Blob(['a']);
    h.run('globalThis.task={id:"upload",name:"a",file,uploadId:"id",uploadedChunks:[],status:"paused"}; transferTasks.set(task.id,task)');
    await h.run('uploadFileResumableManaged(task,()=>task.status==="paused")');
    assert.equal(completed, 0);
    assert.equal(h.run('task.status'), 'paused');
    h.run('task.status="cancelled"');
    await assert.rejects(h.run('uploadFileResumableManaged(task,()=>false)'), /Upload cancelled/);
    assert.equal(completed, 0);
});

test('persistent metadata errors stop after bounded retries without marking data loaded', async () => {
    const h = harness();
    let calls = 0;
    h.window.fetch = async () => { calls++; return response({}, false); };
    h.run('enqueueMetadata(["missing"])');
    await flush();
    assert.equal(calls, 3);
    assert.equal(h.run('metadataLoaded.has("missing")'), false);
    assert.equal(h.run('metadataPending.size'), 0);
    assert.equal(h.run('metadataContext.running'), 0);
});

test('public gallery permissions distinguish view, download, upload and manage', () => {
    const h = harness(true);
    for (const [permission, downloadVisible, uploadVisible] of [
        ['VIEW', false, false], ['DOWNLOAD', true, false], ['UPLOAD', true, true], ['MANAGE', true, true]
    ]) {
        h.context.permission = permission;
        h.run('updatePublicShareButtons(permission)');
        assert.equal(h.elements.get('downloadViewerBtn').style.display, downloadVisible ? 'inline-flex' : 'none');
        assert.equal(h.elements.get('toggleTransfersBtn').style.display, uploadVisible ? 'inline-flex' : 'none');
    }
});

test('existing share lookup filters matching file links', async () => {
    const h = harness();
    h.window.fetch = async () => response([{path: 'photos/a'}, {path: 'photos/b'}]);
    const links = await h.run('loadExistingShareForItem({relativePath:"photos/a"})');
    assert.equal(links.length, 1);
    assert.equal(links[0].path, 'photos/a');
});

test('browser-owned downloads remain terminal after restoring transfer history', () => {
    const h = harness();
    h.run('transferTasks.set("d",{id:"d",kind:"download",name:"a",status:"browser"}); saveTransferTasks(); transferTasks.clear(); restoreTransferTasks()');
    assert.equal(h.run('transferTasks.get("d").status'), 'browser');
    assert.equal(h.run('downloadQueue.length'), 0);
});

test('file names are escaped for HTML and thumbnail placeholders recover failures', () => {
    const h = harness();
    assert.equal(h.run('escapeHtml("<img src=x onerror=1>")'), '&lt;img src=x onerror=1&gt;');
    const image = new Element();
    image.dataset.src = '/missing.jpg';
    h.context.image = image;
    h.run('initLazyThumbs([image])');
    h.observers[0].callback([{target: image, isIntersecting: true}], h.observers[0]);
    assert.equal(image.src, '/missing.jpg');
    image.onerror();
    assert.equal(image.src, '/image-placeholder.png');
    assert.equal(image.onerror, null);
});

test('closing HLS viewer ignores a late preparation response', async () => {
    const h = harness();
    let finish, played = false;
    h.context.playHlsInViewer = () => { played = true; };
    h.window.fetch = () => new Promise(resolve => { finish = resolve; });
    const pending = h.run('openHlsViewer({relativePath:"a.insv",hlsPrepareUrl:"/prepare",hlsStatusUrl:"/status"})');
    h.run('destroyCurrentVideo()');
    finish(response({status: 'READY', playlistUrl: '/playlist'}));
    await pending;
    assert.equal(played, false);
    assert.equal(h.timers.size, 0);
});

test('resuming an upload while its old request settles does not start a second worker', async () => {
    const h = harness();
    let calls = 0;
    h.context.runUploadTask = () => { calls++; };
    h.run('globalThis.task={id:"u",file:{},status:"queued",running:true}; uploadQueue.push(task)');
    await h.run('processUploadQueue()');
    assert.equal(calls, 0);
    assert.equal(h.run('uploadQueue.length'), 1);
    h.run('task.running=false');
    await h.run('processUploadQueue()');
    assert.equal(calls, 1);
});
