let currentPath = "";

const PUBLIC_SHARE_MODE = window.PUBLIC_SHARE_MODE === true;
const SHARE_TOKEN = window.SHARE_TOKEN || null;

function shareApi(path) {
    if (!PUBLIC_SHARE_MODE) {
        return path;
    }

    if (!SHARE_TOKEN) {
        throw new Error("SHARE_TOKEN is missing");
    }

    if (path.startsWith("/api/files?")) {
        return path.replace("/api/files", `/share/${SHARE_TOKEN}`);
    }

    return path
        .replace("/api/files/clear-temp", `/share/${SHARE_TOKEN}/clear-temp`)
        .replace("/api/files/upload/init", `/share/${SHARE_TOKEN}/upload/init`)
        .replace("/api/files/upload/status", `/share/${SHARE_TOKEN}/upload/status`)
        .replace("/api/files/upload/complete", `/share/${SHARE_TOKEN}/upload/complete`)
        .replace("/api/files/upload-chunk", `/share/${SHARE_TOKEN}/upload-chunk`)
        .replace("/api/files/download-selected", `/share/${SHARE_TOKEN}/download-selected`)
        .replace("/api/files/folder", `/share/${SHARE_TOKEN}/folder`)
        .replace("/api/files/prepare-folder", `/share/${SHARE_TOKEN}/prepare-folder`)
        .replace("/api/files/prepare-status", `/share/${SHARE_TOKEN}/prepare-status`)
        .replace("/api/files/prepared-items", `/share/${SHARE_TOKEN}/prepared-items`)
        .replace("/api/files/list", `/share/${SHARE_TOKEN}/list`)
        .replace("/api/files/raw", `/share/${SHARE_TOKEN}/raw`)
        .replace("/api/files/stream", `/share/${SHARE_TOKEN}/stream`)
        .replace("/api/files/download", `/share/${SHARE_TOKEN}/download`)
        .replace("/api/files/image-thumbnail", `/share/${SHARE_TOKEN}/thumbnail`)
        .replace("/api/files/video-thumbnail", `/share/${SHARE_TOKEN}/thumbnail`)
        .replace("/api/video/hls/prepare", `/share/${SHARE_TOKEN}/video/hls/prepare`)
        .replace("/api/video/hls/status", `/share/${SHARE_TOKEN}/video/hls/status`)
        .replace("/api/video/hls/progress", `/share/${SHARE_TOKEN}/video/hls/progress`)
        .replace("/api/video/hls/cancel", `/share/${SHARE_TOKEN}/video/hls/cancel`);
}

function getCookie(name) {
    const value = `; ${document.cookie}`;
    const parts = value.split(`; ${name}=`);

    if (parts.length === 2) {
        return parts.pop().split(";").shift();
    }

    return null;
}

function csrfHeaders(extraHeaders = {}) {

    const token = getCookie("XSRF-TOKEN");
    return {
        ...extraHeaders,

        ...(token
            ? {"X-XSRF-TOKEN": decodeURIComponent(token)}
            : {})
    };
}

async function secureFetch(url, options = {}) {

    url = shareApi(url);

    const method = (options.method || "GET").toUpperCase();

    const unsafe =
        method !== "GET"
        && method !== "HEAD"
        && method !== "OPTIONS";

    return window.fetch(url, {
        credentials: "same-origin",

        ...options,

        headers: unsafe
            ? csrfHeaders(options.headers || {})
            : (options.headers || {})
    });
}

let parentPath = "";
let currentPlayer = null;

let deleteTargetPath = null;
let deleteTargetName = null;

let renameTargetPath = null;
let renameTargetName = null;

let currentPropertiesPath = null;
let currentPropertiesName = null;

let currentItems = [];
let photoSearchState = null;
let photoIndexPolling = null;
const photoSearchForm = document.getElementById("photoSearchForm");
const photoSearchInput = document.getElementById("photoSearchInput");
const photoSearchMessage = document.getElementById("photoSearchMessage");
const photoSearchMore = document.getElementById("photoSearchMore");
const photoSearchClear = document.getElementById("photoSearchClear");
const photoSearchModal = document.getElementById("photoSearchModal");
const photoIndexProgress = document.getElementById("photoIndexProgress");
let lastPhotoIndexStatus = null;
let photoIndexRefreshController = null;
const photoIndexStatus = document.getElementById("photoIndexStatus");
const photoIndexStart = document.getElementById("photoIndexStart");
const photoIndexStop = document.getElementById("photoIndexStop");
let virtualStart = -1;
let virtualEnd = -1;
let virtualTotalCount = -1;
let currentPreparedJobId = null;
let preparedAllLoaded = false;

let groupingMode = localStorage.getItem("groupingMode") || "all";
let periodFilterEnabled = localStorage.getItem("periodFilterEnabled") === "true";
let periodFromValue = localStorage.getItem("periodFromValue") || "";
let periodToValue = localStorage.getItem("periodToValue") || "";
let bulkMoveMode = false;
const selectedItems = new Map();

let currentPreviewId = null;

let sortField = localStorage.getItem("sortField") || "name";
let sortDirection = localStorage.getItem("sortDirection") || "asc";

let metadataLoaded = new Set();

let offset = 0;

let lastLoadTime = 0;
let folderLoadSession = 0;
let activeFolderPath = "";
let backgroundFolderLoading = false;
let folderLoadAbortController = null;
let totalLoadedItems = 0;
let estimatedTotalItems = 0;

let loading = false;
let allLoaded = false;

let activeBulkDownloadId = null;
let bulkDownloadPollingCancelled = false;

const groupingBtn =
    document.getElementById("groupingBtn");

const groupingModal =
    document.getElementById("groupingModal");

const closeGroupingBtn =
    document.getElementById("closeGroupingBtn");

const applyGroupingBtn =
    document.getElementById("applyGroupingBtn");

const resetGroupingBtn =
    document.getElementById("resetGroupingBtn");

const enablePeriodFilter =
    document.getElementById("enablePeriodFilter");

const periodFrom =
    document.getElementById("periodFrom");

const periodTo =
    document.getElementById("periodTo");

const sortBtn = document.getElementById("sortBtn");
const sortModal = document.getElementById("sortModal");
const closeSortModalBtn = document.getElementById("closeSortModalBtn");
const sortDirectionBtn = document.getElementById("sortDirectionBtn");

const propertiesModal = document.getElementById("propertiesModal");
const propertiesBody = document.getElementById("propertiesBody");
const closePropertiesModalBtn = document.getElementById("closePropertiesModalBtn");

const renameModal = document.getElementById("renameModal");
const renameInput = document.getElementById("renameInput");
const renameTargetText = document.getElementById("renameTargetText");

const confirmRenameBtn = document.getElementById("confirmRenameBtn");
const cancelRenameBtn = document.getElementById("cancelRenameBtn");
const closeRenameModalBtn = document.getElementById("closeRenameModalBtn");

const renamePropertiesBtn = document.getElementById("renamePropertiesBtn");
const movePropertiesBtn = document.getElementById("movePropertiesBtn");

const previewBuildModal = document.getElementById("previewBuildModal");
const previewBuildBar = document.getElementById("previewBuildBar");
const previewBuildSize = document.getElementById("previewBuildSize");
const previewBuildText = document.getElementById("previewBuildText");
const previewBuildTime = document.getElementById("previewBuildTime");
const cancelPreviewBuildBtn = document.getElementById("cancelPreviewBuildBtn");

const bulkMoveConfirmModal = document.getElementById("bulkMoveConfirmModal");
const bulkMoveConfirmText = document.getElementById("bulkMoveConfirmText");
const confirmBulkMoveBtn = document.getElementById("confirmBulkMoveBtn");
const cancelBulkMoveBtn = document.getElementById("cancelBulkMoveBtn");

const bulkDownloadBtn = document.getElementById("bulkDownloadBtn");

const bulkDownloadModal = document.getElementById("bulkDownloadModal");
const bulkDownloadText = document.getElementById("bulkDownloadText");
const confirmBulkDownloadBtn = document.getElementById("confirmBulkDownloadBtn");
const cancelBulkDownloadBtn = document.getElementById("cancelBulkDownloadBtn");

const bulkMoveBtn = document.getElementById("bulkMoveBtn");
const bulkDeleteBtn = document.getElementById("bulkDeleteBtn");
const clearSelectionBtn = document.getElementById("clearSelectionBtn");
const selectAllBtn = document.getElementById("selectAllBtn");

const downloadFormatModal = document.getElementById("downloadFormatModal");
const downloadOriginalFormatBtn = document.getElementById("downloadOriginalFormatBtn");
const downloadMp4FormatBtn = document.getElementById("downloadMp4FormatBtn");
const confirmDownloadFormatBtn = document.getElementById("confirmDownloadFormatBtn");
const cancelDownloadFormatBtn = document.getElementById("cancelDownloadFormatBtn");

const metadataLoadingTitle = document.getElementById("metadataLoadingTitle");
const thumbLoadingModal = document.getElementById("thumbLoadingModal");
const thumbLoadingTitle = document.getElementById("thumbLoadingTitle");
const thumbLoadingText = document.getElementById("thumbLoadingText");
const thumbLoadingBar = document.getElementById("thumbLoadingBar");
const thumbLoadingCount = document.getElementById("thumbLoadingCount");

const totalCacheBtn = document.getElementById("totalCacheBtn");
const totalCacheModal = document.getElementById("totalCacheModal");
const totalCacheStage = document.getElementById("totalCacheStage");
const totalCachePath = document.getElementById("totalCachePath");
const totalCacheBar = document.getElementById("totalCacheBar");
const totalCacheCount = document.getElementById("totalCacheCount");
const pauseTotalCacheBtn = document.getElementById("pauseTotalCacheBtn");
const resumeTotalCacheBtn = document.getElementById("resumeTotalCacheBtn");
const closeTotalCacheModalBtn = document.getElementById("closeTotalCacheModalBtn");

const statusTotalCacheBtn = document.getElementById("statusTotalCacheBtn");
const abortTotalCacheBtn = document.getElementById("abortTotalCacheBtn");

const loadingGifOverlay = document.getElementById("loadingGifOverlay");

let totalCacheTimer = null;

let thumbLoadingTotal = 0;
let thumbLoadingDone = 0;
let thumbLoadingSession = 0;
let thumbLoadingHideTimer = null;
let thumbSession = 0;
let folderRingHideTimer = null;

let hlsProgressTimer = null;
let currentHlsProgressItem = null;

const metadataCache = new Map();

let pendingDownloadItem = null;
let pendingDownloadPreviewId = null;
let selectedDownloadFormat = "original";

let metadataTotal = 0;
let metadataProcessed = 0;
const METADATA_QUEUE = [];
const MAX_METADATA_REQUESTS = window.innerWidth <= 768 ? 2 : 4; // 🔥 для телефона критично
const METADATA_BATCH_SIZE = window.innerWidth <= 768 ? 20 : 80;
let currentPreparedTotal = 0;
const PAGE_LIMIT = 100;

function startSequentialPolling(callback, delay, onError = console.error) {
    const controller = new AbortController();
    const polling = {controller, timer: null};
    const tick = async () => {
        if (controller.signal.aborted) return;
        try {
            await callback(controller.signal);
        } catch (error) {
            if (!controller.signal.aborted) onError(error);
        }
        if (!controller.signal.aborted) {
            polling.timer = setTimeout(tick, document.hidden ? Math.max(delay, 5000) : delay);
        }
    };
    polling.timer = setTimeout(tick, delay);
    return polling;
}

function stopPolling(polling) {
    if (!polling) return;
    polling.controller.abort();
    clearTimeout(polling.timer);
}

let metadataContext = {session: 0, running: 0, controller: new AbortController()};
const metadataPending = new Set();
const metadataRetries = new Map();
const cardByPath = new Map();
let viewerInputController = null;
let hlsViewerController = null;
let previewWaitController = null;
let transferSaveTimer = null;
const transferFrames = new Map();

function resetFolderRequests() {
    folderLoadAbortController?.abort();
    folderLoadAbortController = new AbortController();
    metadataContext.controller.abort();
    metadataContext = {session: folderLoadSession, running: 0, controller: new AbortController()};
    METADATA_QUEUE.length = 0;
    metadataPending.clear();
    metadataRetries.clear();
    metadataCache.clear();
    metadataLoaded.clear();
    metadataTotal = 0;
    metadataProcessed = 0;
    window.thumbObserver?.disconnect();
    window.metadataObserver?.disconnect();
    window.thumbObserver = null;
    window.metadataObserver = null;
    thumbSession++;
    cardByPath.clear();
    return folderLoadAbortController.signal;
}

function cleanupViewerInput() {
    viewerInputController?.abort();
    viewerInputController = null;
    viewerDragging = false;
}

function queueTransferProgress(task) {
    if (!transferFrames.has(task.id)) {
        transferFrames.set(task.id, requestAnimationFrame(() => {
            transferFrames.delete(task.id);
            if (transferTasks.has(task.id)) updateTransferTaskUI(task);
        }));
    }
    if (transferSaveTimer === null) {
        transferSaveTimer = setTimeout(() => {
            transferSaveTimer = null;
            saveTransferTasks();
        }, 500);
    }
}

function triggerBrowserDownload(url, name) {
    const link = document.createElement("a");
    link.href = url;
    link.download = name;
    document.body.appendChild(link);
    link.click();
    link.remove();
}

async function waitForPreview(previewId, delay, onStatus = () => {}) {
    previewWaitController?.abort();
    const controller = new AbortController();
    previewWaitController = controller;
    try {
        while (true) {
            const response = await secureFetch(
                `/api/files/preview/status?previewId=${encodeURIComponent(previewId)}`,
                {signal: controller.signal}
            );
            if (!response.ok) throw new Error("Preview status error");
            const status = await response.json();
            controller.signal.throwIfAborted();
            if (status.progress < 0) throw new Error("Preview creation failed");
            onStatus(status);
            if (status.ready) return `/api/files/preview/file?previewId=${encodeURIComponent(previewId)}`;
            await new Promise((resolve, reject) => {
                const abort = () => { clearTimeout(timer); reject(controller.signal.reason); };
                const timer = setTimeout(() => {
                    controller.signal.removeEventListener("abort", abort);
                    resolve();
                }, delay);
                controller.signal.addEventListener("abort", abort, {once: true});
            });
        }
    } finally {
        if (previewWaitController === controller) previewWaitController = null;
    }
}

function hideInPublicMode() {
    if (!PUBLIC_SHARE_MODE) {
        return;
    }

    [
        folderListBtn,
        totalCacheBtn,
        bulkMoveBtn
    ].forEach(e => e?.remove());
}

async function cancelHlsConversion(item) {
    if (!item || !item.relativePath) return;

    if (!(item.name.toLowerCase().endsWith(".insv") || item.name.toLowerCase().endsWith(".lrv"))) {
        return;
    }

    await secureFetch(`/api/video/hls/cancel?path=${encodeURIComponent(item.relativePath)}`, {
        method: "DELETE"
    });
}

function playHlsInViewer(playlistUrl, item) {
    destroyVideoOnly();

    viewerBody.innerHTML = `
<div class="hls-progress-ring" id="hlsProgressRing">
        <svg viewBox="0 0 36 36">
            <path class="hls-progress-bg"
                  d="M18 2.0845
                     a 15.9155 15.9155 0 0 1 0 31.831
                     a 15.9155 15.9155 0 0 1 0 -31.831"/>
            <path class="hls-progress-fill"
                  id="hlsProgressFill"
                  stroke-dasharray="0, 100"
                  d="M18 2.0845
                     a 15.9155 15.9155 0 0 1 0 31.831
                     a 15.9155 15.9155 0 0 1 0 -31.831"/>
        </svg>
        <div class="hls-progress-text" id="hlsProgressText">0%</div>
    </div>

        <video id="player"
               controls
               playsinline
               preload="auto"
               poster="${buildVideoThumbnailUrl(item.relativePath)}"
               style="width:100%; max-height:75vh;">
        </video>
    `;
    startHlsProgressPolling(item);
    const video = document.getElementById("player");

    if (window.Hls && Hls.isSupported()) {
        currentHls = new Hls({
            enableWorker: true,
            lowLatencyMode: false,

            startPosition: 0,

            maxBufferLength: 60,
            maxMaxBufferLength: 120,
            backBufferLength: 30,

            maxBufferHole: 1.5,
            highBufferWatchdogPeriod: 3,

            manifestLoadingTimeOut: 10000,
            manifestLoadingMaxRetry: 999,
            manifestLoadingRetryDelay: 1000,

            levelLoadingMaxRetry: 999,
            fragLoadingMaxRetry: 999,
            fragLoadingRetryDelay: 1000
        });

        currentHls.attachMedia(video);

        currentHls.on(Hls.Events.MEDIA_ATTACHED, () => {

            const hlsUrl =
                playlistUrl +
                (playlistUrl.includes("?") ? "&" : "?") +
                "v=" + Date.now();

            currentHls.loadSource(hlsUrl);

            currentHls.startLoad(0);
        });

        currentHls.on(Hls.Events.MANIFEST_PARSED, () => {

            video.play().catch(console.warn);
        });

        video.addEventListener("waiting", () => {
            if (currentHls) {
                currentHls.startLoad(video.currentTime);
            }
        });

        video.addEventListener("stalled", () => {
            if (currentHls) {
                currentHls.startLoad(video.currentTime);
            }
        });

        currentHls.on(Hls.Events.ERROR, (event, data) => {
            if (!currentHls) return;

            if (
                data.details === "bufferStalledError" ||
                data.details === "bufferNudgeOnStall"
            ) {
                return;
            }

            console.warn("HLS ERROR", data);

            if (!data.fatal) {
                return;
            }

            if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
                currentHls.startLoad();
                return;
            }

            if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
                currentHls.recoverMediaError();
                return;
            }

            destroyCurrentVideo();
        });

        return;
    }

    video.src = playlistUrl;
    currentPlayer = new Plyr(video);

    video.play().catch(console.warn);
}

function destroyVideoOnly() {
    if (currentHls) {
        try {
            currentHls.stopLoad();
            currentHls.detachMedia();
            currentHls.destroy();
        } catch (e) {
            console.warn("HLS destroy warning", e);
        }
        currentHls = null;
    }

    if (currentPlayer) {
        try {
            currentPlayer?.destroy();
        } catch (e) {
            console.warn("Plyr destroy warning", e);
        }
        currentPlayer = null;
    }

    const video = document.getElementById("player");

    if (video) {
        try {
            video.pause();
            video.src = "";
            video.removeAttribute("src");
        } catch (e) {
            console.warn("Video cleanup warning", e);
        }
    }
}

async function updateHlsProgress(item, signal) {
    if (!item || !item.relativePath) return;

    const ring = document.getElementById("hlsProgressRing");
    const fill = document.getElementById("hlsProgressFill");
    const text = document.getElementById("hlsProgressText");

    if (!ring || !fill || !text) return;

    try {
        const response = await secureFetch(
            `/api/video/hls/progress?path=${encodeURIComponent(item.relativePath)}`,
            {signal}
        );

        if (!response.ok) return;

        const state = await response.json();
        if (signal?.aborted || currentHlsProgressItem !== item) return;

        const progress = Math.max(0, Math.min(100, state.progress || 0));

        fill.setAttribute("stroke-dasharray", `${progress}, 100`);
        text.textContent = `${progress}%`;

        if (progress >= 100 || state.ready) {
            ring.classList.add("done");
            stopHlsProgressPolling();
        }

    } catch (e) {
        console.warn("HLS progress error", e);
    }
}

function startHlsProgressPolling(item) {
    stopHlsProgressPolling();

    currentHlsProgressItem = item;

    hlsProgressTimer = startSequentialPolling(signal => updateHlsProgress(item, signal), 1000);
}

function stopHlsProgressPolling() {
    if (hlsProgressTimer) {
        stopPolling(hlsProgressTimer);
        hlsProgressTimer = null;
    }

    currentHlsProgressItem = null;
}

async function waitHlsReady(item, playlistUrl, signal) {
    while (true) {
        await new Promise(resolve => setTimeout(resolve, 1500));

        signal?.throwIfAborted();
        const response = await secureFetch(item.hlsStatusUrl, {signal});

        if (!response.ok) {
            viewerBody.innerHTML = `<div>Ошибка проверки статуса HLS</div>`;
            return;
        }

        const status = await response.text();
        signal?.throwIfAborted();

        if (status.includes("READY") || status.includes("PLAYABLE")) {
            playHlsInViewer(playlistUrl, item);
            return;
        }

        if (status.includes("FAILED")) {
            viewerBody.innerHTML = `<div>Не удалось подготовить HLS-видео</div>`;
            return;
        }

    }
}

let currentHls = null;

function destroyCurrentVideo() {
    hlsViewerController?.abort();
    hlsViewerController = null;
    stopHlsProgressPolling();
    destroyVideoOnly();

}

function renderHlsProgressRing() {
    viewerBody.innerHTML = `
        <div class="hls-progress-ring" id="hlsProgressRing">
            <svg viewBox="0 0 36 36">
                <path class="hls-progress-bg"
                      d="M18 2.0845
                         a 15.9155 15.9155 0 0 1 0 31.831
                         a 15.9155 15.9155 0 0 1 0 -31.831"/>
                <path class="hls-progress-fill"
                      id="hlsProgressFill"
                      stroke-dasharray="0, 100"
                      d="M18 2.0845
                         a 15.9155 15.9155 0 0 1 0 31.831
                         a 15.9155 15.9155 0 0 1 0 -31.831"/>
            </svg>
            <div class="hls-progress-text" id="hlsProgressText">0%</div>
        </div>

        <div class="hls-loading-text">Подготавливаем HLS-видео...</div>
    `;
}

async function openHlsViewer(item) {
    hlsViewerController?.abort();
    const controller = new AbortController();
    hlsViewerController = controller;
    const signal = controller.signal;
    renderHlsProgressRing();
    startHlsProgressPolling(item);
    try {
        const response = await secureFetch(item.hlsPrepareUrl, {method: "POST", signal});
        if (!response.ok) throw new Error("HLS preparation failed");
        const state = await response.json();
        signal.throwIfAborted();
        if (state.status === "READY" || state.status === "PLAYABLE") {
            playHlsInViewer(state.playlistUrl, item);
        } else if (state.status === "RUNNING") {
            await waitHlsReady(item, state.playlistUrl, signal);
        } else {
            throw new Error("HLS preparation failed");
        }
    } catch (error) {
        if (!signal.aborted) {
            viewerBody.textContent = "Не удалось подготовить HLS-видео";
            stopHlsProgressPolling();
        }
    }
}

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

async function openVideo(item) {
    const name = item.name.toLowerCase();

    if (name.endsWith(".insv") || name.endsWith(".lrv")) {
        await openHlsViewer(item);
        return;
    }

    openRegularVideo(item);
}

function closePropertiesModal() {
    if (!propertiesModal) return;

    propertiesModal.classList.add("hidden");
}

async function loadPublicShareFiles(path = "") {
    showLoadingGif();

    try {
        currentPath = path || "";
        activeFolderPath = currentPath;
        parentPath = getParentPath(currentPath);

        currentItems = [];
        viewerItems = [];
        selectedItems.clear();

        gallery.innerHTML = "";
        currentPathEl.textContent = currentPath ? "/" + currentPath : "/";
        currentPathEl.classList.remove("path-expanded");

        const response = await secureFetch(
            `/share/${SHARE_TOKEN}/list?path=${encodeURIComponent(currentPath)}&offset=0&limit=1000`
        );

        if (!response.ok) {
            gallery.innerHTML = `<div class="empty-folder-message">Ссылка недоступна или срок действия истёк</div>`;
            return;
        }

        const data = await response.json();
        window.PUBLIC_SHARE_PERMISSION = data.permission;
        const items = data.items || [];

        renderItems(items);

        if (!items.length) {
            gallery.innerHTML = `<div class="empty-folder-message">Папка пустая</div>`;
        }

        updatePublicShareButtons(data.permission);

        updateNavButtons();

    } finally {
        hideLoadingGif();
    }
}

function updatePublicShareButtons(permission) {
    if (!PUBLIC_SHARE_MODE) return;

    const canUpload = permission === "UPLOAD" || permission === "MANAGE";
    const canDelete = permission === "MANAGE";
    const canDownload =
        permission === "DOWNLOAD" ||
        permission === "UPLOAD" ||
        permission === "MANAGE";

    const canSelect = permission !== "VIEW";

    if (newFolderBtn) {
        newFolderBtn.style.display = canDelete ? "inline-flex" : "none";
    }

    if (folderListBtn) folderListBtn.style.display = "none";
    if (totalCacheBtn) totalCacheBtn.style.display = "none";
    if (bulkMoveBtn) bulkMoveBtn.style.display = "none";

    if (selectAllBtn) {
        selectAllBtn.style.display = canSelect ? "inline-flex" : "none";
    }

    if (clearSelectionBtn) {
        clearSelectionBtn.style.display = canSelect ? "inline-flex" : "none";
    }

    if (bulkDownloadBtn) {
        bulkDownloadBtn.style.display = canSelect ? "inline-flex" : "none";
    }

    if (bulkDeleteBtn) {
        bulkDeleteBtn.style.display = canDelete ? "inline-flex" : "none";
    }

    if (fileInput) {
        const uploadLabel = fileInput.closest(".upload-label");
        if (uploadLabel) {
            uploadLabel.style.display = canUpload ? "inline-flex" : "none";
        }
    }

    if (toggleTransfersBtn) {
        toggleTransfersBtn.style.display = canUpload ? "inline-flex" : "none";
    }

    if (transferPanel && !canUpload) {
        transferPanel.classList.add("hidden");
    }

    document.querySelectorAll(".card-actions .danger").forEach(btn => {
        btn.style.display = canDelete ? "inline-flex" : "none";
    });

    document.querySelectorAll(".card-actions a").forEach(link => {
        link.style.display = canDownload ? "inline-flex" : "none";
    });

    document.querySelectorAll(".item-checkbox").forEach(cb => {
        cb.style.display = canSelect ? "inline-flex" : "none";
    });

    if (downloadViewerBtn) {
        downloadViewerBtn.style.display = canDownload ? "inline-flex" : "none";
    }
}

async function loadFilesPrepared(path = "", options = {}) {
    exitPhotoSearch();

    const sessionId = ++folderLoadSession;
    const signal = resetFolderRequests();
    showLoadingGif();
    try {
        const showPrepareModal = options.showPrepareModal !== false;

// сброс модалок
        hideThumbLoadingModal();
        const pathForLoading = path || "";

        currentPath = pathForLoading;
        activeFolderPath = pathForLoading;
        parentPath = getParentPath(pathForLoading);

        currentPreparedJobId = null;
        preparedAllLoaded = false;
        loading = false;

        offset = 0;
        lastLoadTime = 0;
        currentItems = [];
        viewerItems = [];
        metadataLoaded = new Set();

        virtualStart = -1;
        virtualEnd = -1;
        virtualTotalCount = -1;

        gallery.innerHTML = "";
        gallery.scrollTop = 0;

        await loadSharedLinksIndex(signal);
        if (sessionId !== folderLoadSession) return;

        currentPathEl.textContent = currentPath ? "/" + currentPath : "/";
        currentPathEl.classList.remove("path-expanded");

        if (PUBLIC_SHARE_MODE) {
            const infoRes = await secureFetch(`/share/${SHARE_TOKEN}/info`, {signal});

            if (infoRes.ok) {
                const info = await infoRes.json();
                if (sessionId !== folderLoadSession) return;
                window.PUBLIC_SHARE_PERMISSION = info.permission;
            }
        }

        showFolderLoadingRing(0);

        if (showPrepareModal) {
            showMetadataLoadingModal();
        }

        const res = await secureFetch(

            `/api/files/prepare-folder?path=${encodeURIComponent(pathForLoading)}&sortField=${encodeURIComponent(sortField)}&sortDirection=${encodeURIComponent(sortDirection)}&groupMode=${encodeURIComponent(groupingMode)}&periodEnabled=${periodFilterEnabled}&periodFrom=${encodeURIComponent(periodFromValue)}&periodTo=${encodeURIComponent(periodToValue)}`,
            {method: "POST", signal}
        );

        if (sessionId !== folderLoadSession) return;
        if (!res.ok) {
            gallery.innerHTML = `<div class="empty-folder-message">Ссылка недоступна или срок действия истёк</div>`;
            return;
        }
        const {jobId} = await res.json();
        if (sessionId !== folderLoadSession) return;
        let ready = false;

        while (!ready) {
            const statusRes = await secureFetch(`/api/files/prepare-status?jobId=${encodeURIComponent(jobId)}`, {signal});
            if (!statusRes.ok) throw new Error("Folder preparation failed");
            const status = await statusRes.json();
            if (sessionId !== folderLoadSession) return;
            if (status.error) throw new Error("Подготовка папки истекла. Откройте папку снова.");
            currentPreparedTotal = status.total || 0;
            ready = status.ready;
            updateMetadataLoadingModal(
                status.progress || 0,
                status.processed || 0,
                status.total || 0,
                status.stage || "Подготовка папки"
            );
            updateFolderLoadingRing(status.progress || 0);

            if (!ready) {
                await new Promise(r => setTimeout(r, 300));
            }
        }
        if (sessionId !== folderLoadSession) return;
        hideMetadataLoadingModal();

        currentPreparedJobId = jobId;

        await loadPreparedPage(jobId);

        if (sessionId !== folderLoadSession) return;
        if (PUBLIC_SHARE_MODE) {
            updatePublicShareButtons(window.PUBLIC_SHARE_PERMISSION);
        }

        updateNavButtons();
    } catch (error) {
        if (!signal.aborted && sessionId === folderLoadSession) {
            console.error("Folder loading failed", error);
            gallery.textContent = "Не удалось загрузить папку. Попробуйте ещё раз.";
        }
    } finally {
        if (sessionId === folderLoadSession) {
            hideLoadingGif();
            hideMetadataLoadingModal();
        }
    }
}

function showLoadingGif() {
    if (!loadingGifOverlay) return;

    loadingGifOverlay.style.display = "flex";
    loadingGifOverlay.classList.remove("hidden");
}

function hideLoadingGif() {
    if (!loadingGifOverlay) return;

    loadingGifOverlay.classList.add("hidden");
    loadingGifOverlay.style.display = "none";
}

async function loadPreparedPage(jobId) {
    if (preparedAllLoaded || loading) return;

    const sessionId = folderLoadSession;
    const signal = folderLoadAbortController?.signal;
    const isCurrent = () => sessionId === folderLoadSession && jobId === currentPreparedJobId;
    if (!isCurrent()) return;
    loading = true;

    try {
        const res = await secureFetch(
            `/api/files/prepared-items?jobId=${encodeURIComponent(jobId)}&offset=${offset}&limit=${PAGE_LIMIT}`,
            {signal}
        );

        if (!isCurrent()) return;
        if (!res.ok) {
            throw new Error("Folder page failed");
        }

        const data = await res.json();
        if (!isCurrent()) return;
        const items = data.items || [];

        estimatedTotalItems = data.total || 0;

        if (items.length === 0) {
            preparedAllLoaded = true;
            hideFolderLoadingRing();
            hideLoadingGif();

            if (offset === 0) {
                gallery.innerHTML = `
                    <div class="empty-folder-message">
                        Папка пустая
                    </div>
                `;
            }

            return;
        }

        appendItems(items);

        offset += items.length;

        if (offset >= estimatedTotalItems) {
            preparedAllLoaded = true;
            hideFolderLoadingRing();
            hideLoadingGif();
        } else {
            updateFolderLoadingRing(
                estimatedTotalItems
                    ? Math.round((offset / estimatedTotalItems) * 100)
                    : 0
            );
        }

    } catch (error) {
        if (!signal?.aborted && isCurrent()) console.error("Page loading failed", error);
    } finally {
        if (isCurrent()) loading = false;
    }
}

async function openTotalCacheModal() {

    totalCacheModal.classList.remove("hidden");

    if (totalCacheTimer) {
        stopPolling(totalCacheTimer);
    }

    // просто читаем текущий status
    const response = await secureFetch("/api/files/total-cache/raw-status");

    if (!response.ok) return;

    const status = await response.json();

    renderTotalCacheStatus(status);

    // polling только если процесс уже идёт
    if (status.running || status.stage?.includes("Анализ")) {
        totalCacheTimer = startSequentialPolling(updateTotalCacheStatus, 1000);
    }
}

async function showTotalCacheStatus() {

    await secureFetch("/api/files/total-cache/status-start", {
        method: "POST"
    });

    await secureFetch("/api/files/total-cache/status");

    if (totalCacheTimer) {
        stopPolling(totalCacheTimer);
    }

    totalCacheTimer = startSequentialPolling(updateTotalCacheStatus, 1000);
}

async function pauseTotalCache() {
    await secureFetch("/api/files/total-cache/pause", {
        method: "POST"
    });

    await updateTotalCacheStatus();
}

async function cancelTotalCache() {
    if (totalCacheTimer) {
        stopPolling(totalCacheTimer);
        totalCacheTimer = null;
    }

    await secureFetch("/api/files/total-cache/cancel", {method: "POST"});
    await secureFetch("/api/files/total-cache/reset", {method: "POST"});

    totalCacheModal.classList.add("hidden");
}

async function resumeTotalCache() {
    totalCacheModal.classList.remove("hidden");

    await secureFetch("/api/files/total-cache/start", {
        method: "POST"
    });

    startTotalCachePolling();
}

function startTotalCachePolling() {
    if (totalCacheTimer) {
        stopPolling(totalCacheTimer);
    }

    totalCacheTimer = startSequentialPolling(updateTotalCacheStatus, 1000);
}

function renderTotalCacheStatus(status) {

    totalCacheStage.textContent = status.stage || "Ожидание";
    totalCachePath.textContent = status.currentPath || "";

    const progress = status.progress || 0;

    totalCacheBar.style.width = `${progress}%`;

    const foldersDone = Math.min(
        status.processedFolders || 0,
        status.totalFolders || 0
    );

    const filesDone = Math.min(
        status.processedFiles || 0,
        status.totalFiles || 0
    );

    const thumbsDone = Math.min(
        status.processedThumbnails || 0,
        status.totalThumbnails || 0
    );

    totalCacheCount.textContent =
        `${progress}% · папки ${foldersDone}/${status.totalFolders || 0}, файлы ${filesDone}/${status.totalFiles || 0}, постеры ${thumbsDone}/${status.totalThumbnails || 0}`;

    pauseTotalCacheBtn.disabled = !status.running;
    resumeTotalCacheBtn.disabled = !!status.running;
}

async function updateTotalCacheStatus(signal) {

    const response = await secureFetch("/api/files/total-cache/raw-status", {signal});

    if (!response.ok) return;

    const status = await response.json();

    if (signal?.aborted) return;
    renderTotalCacheStatus(status);

    // остановить polling если всё завершилось
    if (
        !status.running
        && !status.stage?.includes("Анализ")
    ) {
        stopPolling(totalCacheTimer);
        totalCacheTimer = null;
    }
}

async function loadFiles(path = "", options = {}) {
    return loadFilesPrepared(path, options);
}

function exitPhotoSearch() {
    photoSearchState = null;
    if (photoSearchClear) photoSearchClear.hidden = true;
    if (photoSearchMore) photoSearchMore.hidden = true;
    if (photoSearchMessage) photoSearchMessage.textContent = "";
}

function syncPhotoSearchLayout() {
    if (PUBLIC_SHARE_MODE || !photoSearchForm || !topbar) return;
    const container = document.querySelector(".container");
    if (container) container.style.paddingTop = `${Math.ceil(topbar.getBoundingClientRect().height) + 24}px`;
}

async function runPhotoSearch() {
    if (PUBLIC_SHARE_MODE || !photoSearchInput) return;
    const query = photoSearchInput.value.trim();
    if (!query) return;
    ++folderLoadSession;
    resetFolderRequests();
    currentPreparedJobId = null;
    preparedAllLoaded = true;
    loading = false;
    activeFolderPath = currentPath;
    selectedItems.clear();
    metadataLoaded.clear();
    currentItems = [];
    viewerItems = [];
    gallery.innerHTML = "";
    hideMetadataLoadingModal();
    hideThumbLoadingModal();
    hideFolderLoadingRing();
    hideLoadingGif();
    updateBulkButtons();
    currentPathEl.textContent = "Поиск по всей библиотеке";
    photoSearchClear.hidden = false;
    photoSearchState = {query, offset: 0, total: 0, session: folderLoadSession};
    await loadPhotoSearchPage();
}

async function loadPhotoSearchPage() {
    const state = photoSearchState;
    if (!state || loading || PUBLIC_SHARE_MODE) return;
    const signal = folderLoadAbortController?.signal;
    const isCurrent = () => photoSearchState === state && state.session === folderLoadSession;
    loading = true;
    photoSearchMore.hidden = true;
    photoSearchMessage.textContent = "Ищем фотографии…";
    try {
        const response = await secureFetch(`/api/photo-search?query=${encodeURIComponent(state.query)}&offset=${state.offset}&limit=60`, {signal});
        const data = await response.json();
        if (!isCurrent()) return;
        if (!response.ok) throw new Error(data.error || "Не удалось выполнить поиск");
        const items = (data.hits || []).map(hit => ({...hit.item, searchDescription: hit.description}));
        appendItems(items);
        state.offset += items.length;
        state.total = data.total || 0;
        photoSearchMessage.textContent = state.total
            ? `Найдено: ${state.total}. Показано: ${state.offset}. Результаты по близости к запросу.`
            : (data.status?.available ? "Подходящие фото не найдены. Попробуйте другое описание."
                : "Индекс пока пуст. Откройте «Индекс фотографий» и запустите обработку.");
        if (!state.offset) gallery.textContent = "Нет результатов";
        renderPhotoIndexStatus(data.status);
        if (photoSearchModal?.open) closePhotoSearch();
        syncPhotoSearchLayout();
        if (state.offset === items.length) window.scrollTo?.({top: 0});
    } catch (error) {
        if (isCurrent() && !signal?.aborted) photoSearchMessage.textContent = error.message;
    } finally {
        if (isCurrent()) {
            loading = false;
            photoSearchMore.hidden = !(state.offset < state.total && state.offset <= 10000);
        }
    }
}

function renderPhotoIndexStatus(status) {
    if (!status || !photoIndexStatus || PUBLIC_SHARE_MODE) return;
    photoIndexStart.disabled = !status.enabled || status.running;
    photoIndexStop.hidden = !status.running;
    lastPhotoIndexStatus = status;
    const processed = status.processed ?? ((status.indexed || 0) + (status.unchanged || 0));
    const phase = status.running ? status.stage || "Идёт индексация" : status.stage || "Индексация не запущена";
    photoIndexStatus.textContent = `${phase}. Обработано за текущий запуск: ${processed}. Всего в индексе: ${status.available || 0}`;
    photoIndexProgress.hidden = !status.running;
    if (!status.enabled) photoSearchMessage.textContent = "Поиск по фото отключён в настройках сервера.";
    syncPhotoSearchLayout();
}

async function updatePhotoIndexStatus(signal) {
    if (PUBLIC_SHARE_MODE || !photoSearchModal?.open) return;
    const response = await secureFetch("/api/photo-search/status", {signal});
    if (!response.ok) throw new Error("Не удалось получить состояние индекса");
    const status = await response.json();
    if (!signal?.aborted) renderPhotoIndexStatus(status);
}

async function changePhotoIndex(action) {
    if (PUBLIC_SHARE_MODE) return;
    photoIndexStart.disabled = true;
    photoIndexStop.disabled = true;
    try {
        const response = await secureFetch(`/api/photo-search/${action}`, {method: "POST"});
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || "Не удалось изменить состояние индекса");
        renderPhotoIndexStatus(data);
    } catch (error) {
        photoIndexStatus.textContent = "Не удалось обновить состояние индекса. Попробуйте ещё раз.";
        photoIndexStart.disabled = false;
    } finally {
        photoIndexStop.disabled = false;
    }
}

photoSearchForm?.addEventListener("submit", event => { event.preventDefault(); runPhotoSearch(); });
document.getElementById("photoSearchReset")?.addEventListener("click", () => {
    photoSearchInput.value = "";
    photoSearchInput.focus();
});
photoSearchMore?.addEventListener("click", loadPhotoSearchPage);
photoSearchClear?.addEventListener("click", () => { closePhotoSearch(); loadFiles(currentPath); });
photoIndexStart?.addEventListener("click", () => changePhotoIndex("index"));
photoIndexStop?.addEventListener("click", () => changePhotoIndex("stop"));
function stopPhotoIndexPolling() {
    stopPolling(photoIndexPolling);
    photoIndexPolling = null;
    photoIndexRefreshController?.abort();
    photoIndexRefreshController = null;
}

function refreshPhotoIndexPolling() {
    stopPhotoIndexPolling();
    if (PUBLIC_SHARE_MODE || !photoSearchModal?.open) return;
    const controller = new AbortController();
    photoIndexRefreshController = controller;
    const onError = () => {
        if (!controller.signal.aborted) photoIndexStatus.textContent = "Связь с сервером прервана. Повторяем подключение…";
    };
    // Wait for the first request before scheduling the next; never overlap requests.
    updatePhotoIndexStatus(controller.signal).catch(onError).finally(() => {
        if (!controller.signal.aborted) photoIndexPolling = startSequentialPolling(updatePhotoIndexStatus, 2000, onError);
    });
}

function openPhotoSearch() {
    if (PUBLIC_SHARE_MODE || !photoSearchModal || photoSearchModal.open) return;
    photoSearchModal.showModal();
    if (lastPhotoIndexStatus) renderPhotoIndexStatus(lastPhotoIndexStatus);
    refreshPhotoIndexPolling();
    photoSearchInput.focus();
}

function closePhotoSearch() {
    photoSearchModal?.close();
    stopPhotoIndexPolling();
}

document.getElementById("photoSearchOpen")?.addEventListener("click", openPhotoSearch);
document.getElementById("photoSearchClose")?.addEventListener("click", closePhotoSearch);
photoSearchModal?.addEventListener("close", stopPhotoIndexPolling);
photoSearchModal?.addEventListener("click", event => {
    if (event.target !== photoSearchModal) return;
    const rect = photoSearchModal.getBoundingClientRect();
    if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) closePhotoSearch();
});
window.addEventListener("pagehide", stopPhotoIndexPolling);
window.addEventListener("pageshow", refreshPhotoIndexPolling);
document.addEventListener("visibilitychange", () => {
    if (!document.hidden) refreshPhotoIndexPolling();
});

async function maybeLoadMorePrepared(jobId = currentPreparedJobId) {
    if (!jobId || jobId !== currentPreparedJobId) return;
    if (preparedAllLoaded || loading) return;

    const now = Date.now();
    if (now - lastLoadTime < 400) return;

    const totalLoaded = currentItems.length;
    const visibleEnd = virtualEnd || 0;

    const isMobile = window.innerWidth <= 768;
    const preloadItems = isMobile ? 20 : getGalleryColumns() * 8;

    if (totalLoaded - visibleEnd <= preloadItems) {
        lastLoadTime = now;
        await loadPreparedPage(jobId);
    }
}

function openRenameModal(path, name) {
    renameTargetPath = path;
    renameTargetName = name;

    renameTargetText.textContent = `Переименование: ${name}`;
    renameInput.value = name;

    renameModal.classList.remove("hidden");

    setTimeout(() => {
        renameInput.focus();
        renameInput.select();
    }, 40);
}

function closeRenameModal() {
    renameModal.classList.add("hidden");

    renameTargetPath = null;
    renameTargetName = null;

    renameInput.value = "";
}

groupingBtn?.addEventListener("click", () => {
    groupingModal?.classList.remove("hidden");
});

closeGroupingBtn?.addEventListener("click", () => {
    groupingModal?.classList.add("hidden");
});

applyGroupingBtn.onclick = () => {
    periodFilterEnabled = enablePeriodFilter.checked;
    periodFromValue = periodFrom.value || "";
    periodToValue = periodTo.value || "";

    localStorage.setItem("groupingMode", groupingMode);
    localStorage.setItem("periodFilterEnabled", periodFilterEnabled);
    localStorage.setItem("periodFromValue", periodFromValue);
    localStorage.setItem("periodToValue", periodToValue);

    groupingBtn.classList.toggle(
        "active",
        groupingMode !== "all" || periodFilterEnabled
    );

    groupingModal.classList.add("hidden");

    loadFiles(currentPath);
};

function resetGroupingState() {

    groupingMode = "all";
    periodFilterEnabled = false;
    periodFromValue = "";
    periodToValue = "";

    localStorage.removeItem("groupingMode");
    localStorage.removeItem("periodFilterEnabled");
    localStorage.removeItem("periodFromValue");
    localStorage.removeItem("periodToValue");

    groupingBtn.classList.remove("active");
}

resetGroupingBtn.onclick = () => {
    groupingMode = "all";
    periodFilterEnabled = false;
    periodFromValue = "";
    periodToValue = "";

    localStorage.removeItem("groupingMode");
    localStorage.removeItem("periodFilterEnabled");
    localStorage.removeItem("periodFromValue");
    localStorage.removeItem("periodToValue");

    groupingBtn.classList.remove("active");

    groupingModal.classList.add("hidden");

    loadFiles(currentPath);
};
cancelRenameBtn.onclick = closeRenameModal;
closeRenameModalBtn.onclick = closeRenameModal;

function showToast(message) {
    const toast = document.getElementById("toast");

    if (!toast) {
        console.warn("Toast not found");
        return;
    }

    toast.textContent = message;
    toast.classList.remove("hidden");

    requestAnimationFrame(() => {
        toast.classList.add("show");
    });

    setTimeout(() => {
        toast.classList.remove("show");
        setTimeout(() => toast.classList.add("hidden"), 250);
    }, 3000);
}

function showDownloadActionToast(item) {
    const toast = document.getElementById("actionToast");
    const text = toast.querySelector(".toast-text");

    const btnDownload = document.getElementById("toastCloseAndDownload");
    const btnGoToFile = document.getElementById("toastGoToFile");

    text.textContent = "Чтобы скачать файл в оригинальном формате, выйдите из режима просмотра и скачайте его с карточки";

    toast.classList.remove("hidden");

    requestAnimationFrame(() => {
        toast.classList.add("show");
    });

    // 🔹 Закрыть viewer и скачать
    btnDownload.onclick = () => {
        closeViewerModal();

        setTimeout(() => {
            window.location.href = item.downloadUrl;
        }, 300);

        hideActionToast();
    };

    // 🔹 Перейти к файлу
    btnGoToFile.onclick = () => {
        closeViewerModal();

        setTimeout(() => {
            scrollToFile(item.relativePath);
        }, 300);

        hideActionToast();
    };

    // автоскрытие (опционально)
    setTimeout(() => {
        hideActionToast();
    }, 5000);
}

function getParentPath(path) {
    if (!path) return "";

    const parts = path.split("/").filter(Boolean);
    parts.pop();

    return parts.join("/");
}

async function fetchMetadataBulk(paths) {
    if (!paths || paths.length === 0) return;

    const response = await secureFetch("/api/files/metadata/card-bulk", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(paths)
    });

    if (!response.ok) return;

    const data = await response.json();

    Object.entries(data).forEach(([path, meta]) => {
        const card = cardByPath.get(path);
        if (!card || !meta) return;

        const metaEl = card.querySelector(".meta");
        const dateEl = card.querySelector(".card-created-date");

        if (!metaEl) return;

        if (meta.directory) {
            if (meta.fileCount == null && meta.folderCount == null) return;
            const hasContent = (meta.fileCount || 0) > 0 || (meta.folderCount || 0) > 0;
            metaEl.innerHTML = hasContent
                ? "Папка с файлами"
                : "Пустая папка";

        } else if (dateEl && meta.createdAt) {
            dateEl.textContent = " · " + formatDateTime(meta.createdAt);
            dateEl.dataset.createdAt = meta.createdAt;
        }
    });
}

function applyCardMetadata(path, meta) {
    const card = cardByPath.get(path);
    if (!card || !meta) return;

    const metaEl = card.querySelector(".meta");
    const dateEl = card.querySelector(".card-created-date");
    if (meta.directory) {
        if (!metaEl) return;

        if (meta.fileCount == null && meta.folderCount == null) return;

        const hasContent = (meta.fileCount || 0) > 0 || (meta.folderCount || 0) > 0;

        metaEl.innerHTML = hasContent
            ? "Папка с файлами"
            : "Пустая папка";

        return;
    }
    if (metaEl && meta.createdAt) {
        let dateEl = metaEl.querySelector(".card-created-date");

        if (!dateEl) {
            dateEl = document.createElement("span");
            dateEl.className = "card-created-date";
            metaEl.appendChild(dateEl);
        }

        dateEl.textContent = " · " + formatDateTime(meta.createdAt);
    }
}

async function processMetadataQueue(context = metadataContext) {
    while (context === metadataContext && context.running < MAX_METADATA_REQUESTS && METADATA_QUEUE.length) {
        const batch = METADATA_QUEUE.splice(0, METADATA_BATCH_SIZE);
        context.running++;
        (async () => {
            try {
                const response = await secureFetch("/api/files/metadata/card-bulk", {
                    method: "POST", headers: {"Content-Type": "application/json"},
                    body: JSON.stringify(batch), signal: context.controller.signal
                });
                if (!response.ok) throw new Error("Metadata request failed");
                const data = await response.json();
                if (context !== metadataContext) return;
                for (const path of batch) {
                    if (data[path]) {
                        metadataCache.set(path, data[path]);
                        metadataLoaded.add(path);
                        applyCardMetadata(path, data[path]);
                    }
                    metadataPending.delete(path);
                }
                metadataProcessed += batch.length;
            } catch (error) {
                if (context !== metadataContext || context.controller.signal.aborted) return;
                for (const path of batch) {
                    metadataPending.delete(path);
                    const attempts = metadataRetries.get(path) || 0;
                    if (attempts < 2) {
                        metadataRetries.set(path, attempts + 1);
                        metadataPending.add(path);
                        METADATA_QUEUE.push(path);
                    }
                }
                console.warn("Metadata request failed", error);
            } finally {
                context.running--;
                if (context === metadataContext) processMetadataQueue(context);
            }
        })();
    }
}

function enqueueMetadata(paths) {
    for (const path of paths) {
        if (!path) continue;
        if (metadataCache.has(path)) {
            applyCardMetadata(path, metadataCache.get(path));
        } else if (!metadataLoaded.has(path) && !metadataPending.has(path)) {
            metadataPending.add(path);
            METADATA_QUEUE.push(path);
            metadataTotal++;
        }
    }
    processMetadataQueue();
}

function scrollToFile(path) {
    const el = document.querySelector(`.card[data-path="${CSS.escape(path)}"]`);
    if (!el) return;

    el.scrollIntoView({
        behavior: "smooth",
        block: "center"
    });

    el.classList.add("highlight");

    setTimeout(() => {
        el.classList.remove("highlight");
    }, 1500);
}

function hideActionToast() {
    const toast = document.getElementById("actionToast");

    toast.classList.remove("show");

    setTimeout(() => {
        toast.classList.add("hidden");
    }, 250);
}

function openDownloadFormatModal(item, previewId = null) {
    pendingDownloadItem = item;
    pendingDownloadPreviewId = previewId;
    selectedDownloadFormat = "original";

    updateDownloadFormatButtons();

    downloadFormatModal.classList.remove("hidden");
}

function closeDownloadFormatModal() {
    downloadFormatModal.classList.add("hidden");
    pendingDownloadItem = null;
    pendingDownloadPreviewId = null;
    selectedDownloadFormat = "original";
}

function updateDownloadFormatButtons() {
    downloadOriginalFormatBtn.classList.toggle("active", selectedDownloadFormat === "original");
    downloadMp4FormatBtn.classList.toggle("active", selectedDownloadFormat === "mp4");
}

function updateBulkButtons() {
    const count = selectedItems.size;
    const hasSelection = count > 0;

    clearSelectionBtn.hidden = !hasSelection;
    bulkMoveBtn.hidden = !hasSelection;
    bulkDownloadBtn.hidden = !hasSelection;
    bulkDeleteBtn.hidden = !hasSelection;

    bulkMoveBtn.disabled = !hasSelection;
    bulkDeleteBtn.disabled = !hasSelection;
    clearSelectionBtn.disabled = !hasSelection;
    bulkDownloadBtn.disabled = !hasSelection;

    bulkDownloadBtn.textContent =
        count ? `Скачать выбранные (${count})` : "Скачать";

    bulkMoveBtn.textContent =
        count ? `Переместить выбранные (${count})` : "Переместить";

    bulkDeleteBtn.textContent =
        count ? `Удалить выбранные (${count})` : "Удалить";

    clearSelectionBtn.textContent =
        count ? `Снять выбор (${count})` : "Снять";
}

const shareModal = document.getElementById("shareModal");
const closeShareModalBtn = document.getElementById("closeShareModalBtn");
const cancelShareBtn = document.getElementById("cancelShareBtn");
const createShareBtn = document.getElementById("createShareBtn");
const shareTargetName = document.getElementById("shareTargetName");
const shareExpiresSelect = document.getElementById("shareExpiresSelect");
const shareResultBox = document.getElementById("shareResultBox");
const shareUrlInput = document.getElementById("shareUrlInput");
const copyShareUrlBtn = document.getElementById("copyShareUrlBtn");
const shareExistingLinksBox = document.getElementById("shareExistingLinksBox");
const shareExistingLinksList = document.getElementById("shareExistingLinksList");

const shareDuplicateModal = document.getElementById("shareDuplicateModal");
const duplicateShareUrlInput = document.getElementById("duplicateShareUrlInput");
const copyDuplicateShareBtn = document.getElementById("copyDuplicateShareBtn");
const closeDuplicateShareBtn = document.getElementById("closeDuplicateShareBtn");
const closeDuplicateShareModalBtn = document.getElementById("closeDuplicateShareModalBtn");

let shareTargetLinks = [];
let sharedLinksByPath = new Map();

const disableShareUrlBtn = document.getElementById("disableShareUrlBtn");

let shareTargetItem = null;
let currentShareToken = null;

//функция сортировки
function sortItems(items) {
    return [...items].sort((a, b) => {
        // папки сверху
        if (a.directory && !b.directory) return -1;
        if (!a.directory && b.directory) return 1;

        let result = 0;

        if (sortField === "name") {
            result = a.name.localeCompare(b.name, "ru", {numeric: true});
        }

        if (sortField === "lastModified") {

            result = (a.createdAt || a.lastModified || 0) - (b.createdAt || b.lastModified || 0);

        }

        if (sortField === "size") {
            result = (a.size || 0) - (b.size || 0);
        }

        return sortDirection === "asc" ? result : -result;
    });
}

function updateSortButtonsState() {
    document.querySelectorAll(".sort-field-btn").forEach(btn => {
        btn.classList.toggle("active", btn.dataset.field === sortField);
    });

    sortDirectionBtn.classList.toggle("asc", sortDirection === "asc");
    sortDirectionBtn.classList.toggle("desc", sortDirection === "desc");

    sortDirectionBtn.textContent =
        sortDirection === "asc"
            ? "↑ От меньшего к большему"
            : "↓ От большего к меньшему";
}

groupingBtn.onclick = () => {
    enablePeriodFilter.checked = periodFilterEnabled;
    periodFrom.value = periodFromValue;
    periodTo.value = periodToValue;

    document.querySelectorAll(".group-mode-btn").forEach(btn => {
        btn.classList.toggle("active", btn.dataset.mode === groupingMode);
    });

    groupingModal.classList.remove("hidden");
};
document.querySelectorAll(".group-mode-btn").forEach(btn => {
    btn.onclick = () => {
        document.querySelectorAll(".group-mode-btn")
            .forEach(b => b.classList.remove("active"));

        btn.classList.add("active");
        groupingMode = btn.dataset.mode;
    };
});

document.querySelectorAll(".group-mode-btn")
    .forEach(btn => {

        btn.onclick = () => {

            document
                .querySelectorAll(".group-mode-btn")
                .forEach(b => b.classList.remove("active"));

            btn.classList.add("active");

            groupingMode = btn.dataset.mode;
        };
    });
document.querySelectorAll(".sort-field-btn").forEach(btn => {
    btn.onclick = () => {
        sortField = btn.dataset.field;

        localStorage.setItem("sortField", sortField);
        localStorage.setItem("sortDirection", sortDirection);

        updateSortButtonsState();
        loadFiles(currentPath);
    };
});

function openBulkDownloadModal() {
    if (!selectedItems.size) return;

    bulkDownloadText.textContent =
        `Скачать ${selectedItems.size} выбранных объектов одним архивом?`;

    bulkDownloadModal.classList.remove("hidden");
}

function formatDateTime(timestamp) {
    if (!timestamp) return "";

    return new Date(timestamp).toLocaleString("ru-RU", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
    });
}

async function preparePreviewVideo(item) {
    previewBuildModal.classList.remove("hidden");

    previewBuildSize.textContent = "Обработано: 0 MB";

    previewBuildTime.textContent = "Это может занять время для длинных видео";

    const form = new URLSearchParams();
    form.append("path", item.relativePath);

    const startResponse = await secureFetch("/api/files/preview/start", {
        method: "POST",
        headers: {
            "Content-Type": "application/x-www-form-urlencoded"
        },
        body: form
    });

    if (!startResponse.ok) {
        previewBuildModal.classList.add("hidden");
        alert("Не удалось начать создание preview-видео");
        return null;
    }

    const data = await startResponse.json();
    currentPreviewId = data.previewId;

    try {
        return await waitForPreview(currentPreviewId, 1000, status => {
            previewBuildSize.textContent = `Обработано: ${formatFileSize(status.size || 0)}`;
        });
    } finally {
        previewBuildModal.classList.add("hidden");
    }

}

async function loadSharedLinksIndex(signal) {
    const session = folderLoadSession;
    if (PUBLIC_SHARE_MODE) return;

    try {
        const response = await secureFetch("/api/share", {signal});

        if (session !== folderLoadSession || signal?.aborted) return;
        if (!response.ok) {
            sharedLinksByPath = new Map();
            return;
        }

        const links = await response.json();
        if (session !== folderLoadSession || signal?.aborted) return;

        sharedLinksByPath = new Map();

        for (const link of links) {
            if (!link.path) continue;

            if (!sharedLinksByPath.has(link.path)) {
                sharedLinksByPath.set(link.path, []);
            }

            sharedLinksByPath.get(link.path).push(link);
        }

    } catch (e) {
        if (session !== folderLoadSession || signal?.aborted) return;
        console.warn("Share index load failed", e);
        sharedLinksByPath = new Map();
    }
}

async function loadExistingShareForItem(item) {
    try {
        const response = await secureFetch("/api/share");

        if (!response.ok) {
            return [];
        }

        const links = await response.json();

        return links.filter(link =>
            link.path === item.relativePath
        );

    } catch (e) {
        console.warn("Existing share load failed", e);
        return [];
    }
}

function getPermissionTitle(permission) {
    switch (permission) {
        case "VIEW":
            return "Только просмотр";
        case "DOWNLOAD":
            return "Просмотр и скачивание";
        case "UPLOAD":
            return "Смотреть, скачивать, загружать";
        case "MANAGE":
            return "Полный доступ";
        default:
            return permission;
    }
}

function renderExistingShareLinks() {
    if (!shareExistingLinksBox || !shareExistingLinksList) return;

    shareExistingLinksList.innerHTML = "";

    if (!shareTargetLinks.length) {
        shareExistingLinksBox.classList.add("hidden");
        return;
    }

    shareExistingLinksBox.classList.remove("hidden");

    for (const link of shareTargetLinks) {
        const row = document.createElement("div");
        row.className = "share-existing-link-row";

        row.innerHTML = `
            <div class="share-existing-link-info">
                <b>${getPermissionTitle(link.permission)}</b>
                <input class="share-url-input" value="${escapeHtml(link.url)}" readonly>
            </div>
            <div class="share-existing-link-actions">
                <button type="button" data-action="copy">Копировать</button>
                <button type="button" data-action="delete" class="danger">Удалить</button>
            </div>
        `;

        row.querySelector('[data-action="copy"]').onclick = async () => {
            await copyText(link.url);
            showToast("ссылка скопирована");
        };

        row.querySelector('[data-action="delete"]').onclick = async () => {
            await deleteShareLinkByToken(link.token);
        };

        shareExistingLinksList.appendChild(row);
    }
}

async function copyText(text) {
    try {
        await navigator.clipboard.writeText(text);
    } catch (e) {
        const input = document.createElement("input");
        input.value = text;
        document.body.appendChild(input);
        input.select();
        document.execCommand("copy");
        input.remove();
    }
}

async function executeBulkDownload() {
    if (!selectedItems.size) {
        return;
    }

    bulkDownloadPollingCancelled = false;
    activeBulkDownloadId = null;

    const formData = new URLSearchParams();

    for (const item of selectedItems.values()) {
        formData.append("paths", item.path);
    }

    bulkDownloadText.textContent =
        "Определяем размер и подготавливаем архив...";

    confirmBulkDownloadBtn.disabled = true;

    // Кнопку отмены не блокируем
    cancelBulkDownloadBtn.disabled = false;

    try {
        const response = await secureFetch(
            "/api/files/download-selected/prepare",
            {
                method: "POST",
                headers: {
                    "Content-Type":
                        "application/x-www-form-urlencoded"
                },
                body: formData
            }
        );

        if (!response.ok) {
            const errorText = await response.text();

            throw new Error(
                errorText
                || `Ошибка подготовки: ${response.status}`
            );
        }

        const data = await response.json();

        const downloadId = data.downloadId;
        activeBulkDownloadId = downloadId;

        bulkDownloadText.textContent =
            `Подготавливаем архив из `
            + `${data.totalItems} объектов. `
            + `Размер файлов: `
            + `${formatFileSize(data.totalBytes)}`;

        await waitBulkDownloadReady(downloadId);

    } catch (error) {
        console.error(
            "Ошибка подготовки ZIP:",
            error
        );

        // При обычном закрытии окна не показываем ошибку
        if (bulkDownloadPollingCancelled) {
            return;
        }

        bulkDownloadText.textContent =
            "Не удалось подготовить архив";

        confirmBulkDownloadBtn.disabled = false;
        cancelBulkDownloadBtn.disabled = false;

        showToast(
            error.message || "Ошибка подготовки архива"
        );
    }
}
async function waitBulkDownloadReady(downloadId) {
    while (!bulkDownloadPollingCancelled) {
        await new Promise(resolve =>
            setTimeout(resolve, 1000)
        );

        // За время ожидания пользователь мог нажать отмену
        if (bulkDownloadPollingCancelled) {
            return;
        }

        const response = await secureFetch(
            `/api/files/download-selected/status`
            + `?downloadId=${encodeURIComponent(downloadId)}`
        );

        if (!response.ok) {
            throw new Error(
                "Ошибка проверки подготовки архива"
            );
        }

        const status = await response.json();

        bulkDownloadText.textContent =
            `Подготовка архива: ${status.progress}%`
            + ` · ${formatFileSize(status.processedBytes)}`
            + ` из ${formatFileSize(status.totalBytes)}`;

        if (status.status === "ERROR") {
            throw new Error(
                status.error
                || "Не удалось сформировать архив"
            );
        }

        if (status.status === "READY") {
            bulkDownloadText.textContent =
                `Архив готов: `
                + `${formatFileSize(status.zipSize)}`;

            activeBulkDownloadId = null;

            startPreparedZipDownload(downloadId);
            return;
        }
    }
}
function startPreparedZipDownload(downloadId) {
    const url = shareApi(
        `/api/files/download-selected/file`
        + `?downloadId=${encodeURIComponent(downloadId)}`
    );

    const link = document.createElement("a");

    link.href = url;
    link.download = "selected-files.zip";

    document.body.appendChild(link);
    link.click();
    link.remove();

    activeBulkDownloadId = null;
    bulkDownloadPollingCancelled = false;

    bulkDownloadModal.classList.add("hidden");

    confirmBulkDownloadBtn.disabled = false;
    cancelBulkDownloadBtn.disabled = false;
}
function openBulkDeleteModal() {
    if (!selectedItems.size) return;

    deleteTargetPath = null;
    deleteTargetName = null;

    deleteTargetNameEl.textContent = `Выбрано объектов: ${selectedItems.size}`;
    document.querySelector(".delete-warning").textContent =
        `Подтвердите удаление ${selectedItems.size} выбранных объектов. Это действие нельзя отменить.`;

    deleteModal.classList.remove("hidden");
}

let ignoreMoveConfirmBackdropUntil = 0;

function openMoveConfirmModalSafe() {
    ignoreMoveConfirmBackdropUntil = Date.now() + 600;
    bulkMoveConfirmModal.classList.remove("hidden");
}

function confirmMove() {
    const targetText = selectedMovePath ? "/" + selectedMovePath : "/";

    if (bulkMoveMode) {
        bulkMoveConfirmText.textContent =
            `Переместить ${selectedItems.size} выбранных объектов в папку ${targetText}?`;
    } else {
        bulkMoveConfirmText.textContent =
            `Переместить "${moveSourceName}" в папку ${targetText}?`;
    }

    openMoveConfirmModalSafe();
}

async function executeSingleMove() {
    showLoadingGif();

    try {
        if (!moveSourcePath) return;

        const formData = new URLSearchParams();
        formData.append("sourcePath", moveSourcePath);
        formData.append("targetPath", selectedMovePath);

        const response = await secureFetch("/api/files/move", {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded"
            },
            body: formData
        });

        if (!response.ok) {
            const text = await response.text();
            alert("Не удалось переместить:\n" + text);
            return;
        }

        bulkMoveConfirmModal.classList.add("hidden");
        closeMoveModal();

        await loadFiles(currentPath);

    } finally {
        hideLoadingGif();
    }
}

async function executeBulkMove() {
    showLoadingGif();

    try {
        const count = selectedItems.size;

        for (const item of selectedItems.values()) {
            const formData = new URLSearchParams();
            formData.append("sourcePath", item.path);
            formData.append("targetPath", selectedMovePath);

            const response = await secureFetch("/api/files/move", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: formData
            });

            if (!response.ok) {
                const text = await response.text();
                alert(`Не удалось переместить "${item.name}":\n` + text);
                return;
            }
        }

        selectedItems.clear();
        bulkMoveMode = false;

        bulkMoveConfirmModal.classList.add("hidden");
        closeMoveModal();

        await loadFiles(currentPath);
        updateBulkButtons();
    } finally {

        hideLoadingGif();
    }
}

const transferPanel = document.getElementById("transferPanel");
const transferList = document.getElementById("transferList");
const resumeAllTransfersBtn = document.getElementById("resumeAllTransfersBtn");

resumeAllTransfersBtn.textContent = "⏸ Пауза все";
transferList.addEventListener("click", (e) => {
    const removeBtn = e.target.closest(".transfer-remove");
    if (removeBtn) {
        e.preventDefault();
        e.stopPropagation();

        const id = removeBtn.dataset.id;
        const task = transferTasks.get(id);
        if (!task) return;

        // помечаем как отменённую
        task.controller?.abort();
        task.status = "cancelled";

        // убираем из очередей
        const u = uploadQueue.findIndex(t => t.id === id);
        if (u >= 0) uploadQueue.splice(u, 1);

        const d = downloadQueue.findIndex(t => t.id === id);
        if (d >= 0) downloadQueue.splice(d, 1);

        transferTasks.delete(id);
        saveTransferTasks();
        renderTransferList();
        updateTopProgress();
        return;
    }

    const controlBtn = e.target.closest(".control");
    if (controlBtn) {
        e.preventDefault();
        e.stopPropagation();

        const id = controlBtn.dataset.id;
        const task = transferTasks.get(id);
        if (!task) return;

        // если задача в очереди — ставим на паузу
        if (task.status === "queued") {
            task.status = "paused";
            task.controller?.abort();

            const index = uploadQueue.findIndex(t => t.id === id);
            if (index >= 0) uploadQueue.splice(index, 1);
            const downloadIndex = downloadQueue.findIndex(t => t.id === id);
            if (downloadIndex >= 0) downloadQueue.splice(downloadIndex, 1);

            saveTransferTasks();
            renderTransferList();
            return;
        }

        // если задача уже грузится — ставим на паузу
        if (task.status === "uploading") {
            task.status = "paused";
            task.controller?.abort();

            saveTransferTasks();
            renderTransferList();

            // освобождаем слот для следующего файла из очереди
            processUploadQueue();
            return;
        }

        // если задача на паузе — возвращаем в очередь и пытаемся стартовать
        if (task.status === "paused") {
            task.status = "queued";

            if (task.kind === "upload" && task.file && !uploadQueue.find(t => t.id === id)) {
                uploadQueue.push(task);
            }

            if (task.kind === "download" && !downloadQueue.includes(task)) downloadQueue.push(task);
            saveTransferTasks();
            renderTransferList();
            processUploadQueue();
            processDownloadQueue();
            return;
        }
    }
});

totalCacheBtn?.addEventListener("click", openTotalCacheModal);

statusTotalCacheBtn?.addEventListener("click", showTotalCacheStatus);
pauseTotalCacheBtn?.addEventListener("click", pauseTotalCache);
resumeTotalCacheBtn?.addEventListener("click", resumeTotalCache);

// СКРЫТЬ — только закрывает модалку, процессы продолжаются
closeTotalCacheModalBtn.onclick = () => {
    if (totalCacheTimer) {
        stopPolling(totalCacheTimer);
        totalCacheTimer = null;
    }

    totalCacheModal.classList.add("hidden");
};

// ✕ — аварийно останавливает кеширование + анализ и закрывает модалку
abortTotalCacheBtn.onclick = () => {
    if (totalCacheTimer) {
        stopPolling(totalCacheTimer);
        totalCacheTimer = null;
    }

    totalCacheModal.classList.add("hidden");

    secureFetch("/api/files/total-cache/abort", {
        method: "POST"
    }).catch(console.error);
};
confirmRenameBtn.onclick = async () => {
    showLoadingGif();

    try {
        if (!renameTargetPath) return;

        const newName = renameInput.value.trim();

        if (!newName) return;

        const form = new URLSearchParams();

        form.append("path", renameTargetPath);
        form.append("newName", newName);

        const response = await secureFetch("/api/files/rename", {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded"
            },
            body: form
        });

        if (!response.ok) {
            const text = await response.text();

            alert("Ошибка переименования:\n" + text);
            return;
        }

        closeRenameModal();

        await loadFiles(currentPath);
    } finally {

        hideLoadingGif();
    }
};
renamePropertiesBtn.onclick = () => {
    closePropertiesModal();

    openRenameModal(
        currentPropertiesPath,
        currentPropertiesName
    );
};

movePropertiesBtn.onclick = () => {
    closePropertiesModal();

    openMoveModal(
        currentPropertiesPath,
        currentPropertiesName
    );
};
resumeAllTransfersBtn.onclick = () => {
    allTransfersPaused = !allTransfersPaused;

    if (allTransfersPaused) {
        // поставить все на паузу
        for (const task of transferTasks.values()) {
            if (task.status === "uploading" || task.status === "queued") {
                task.status = "paused";
                task.controller?.abort();
            }
        }

        uploadQueue.length = 0;
        downloadQueue.length = 0;

        resumeAllTransfersBtn.classList.remove("resume-all-active");
        resumeAllTransfersBtn.classList.add("resume-all-paused");
        resumeAllTransfersBtn.textContent = "▶ Запустить все";
    } else {
        // запустить все
        for (const task of transferTasks.values()) {
            if (task.status === "paused") {
                task.status = "queued";

                if (task.kind === "upload" && task.file && !uploadQueue.find(t => t.id === task.id)) {
                    uploadQueue.push(task);
                }

                if (task.kind === "download" && !downloadQueue.find(t => t.id === task.id)) {
                    downloadQueue.push(task);
                }
            }
        }

        resumeAllTransfersBtn.classList.remove("resume-all-paused");
        resumeAllTransfersBtn.classList.add("resume-all-active");
        resumeAllTransfersBtn.textContent = "⏸ Пауза все";

        processUploadQueue();
        processDownloadQueue();
    }

    saveTransferTasks();
    renderTransferList();
};

const uploadQueue = [];
const downloadQueue = [];
const transferTasks = new Map();
const TRANSFERS_STORAGE_KEY = "gallery_transfer_tasks_v1";

let activeUploads = 0;
let activeDownloads = 0;

let cancelAllTransfers = false;
let allTransfersPaused = false;
const MAX_PARALLEL = 3;

// текущий список файлов, которые можно просматривать
let viewerItems = [];
let viewerIndex = -1;
let moveSourcePath = null;
let moveSourceName = null;
let selectedMovePath = "";

let viewerZoom = 1;
let viewerPanX = 0;
let viewerPanY = 0;
let viewerDragging = false;
let viewerDragStartX = 0;
let viewerDragStartY = 0;
let viewerLastTouchDistance = null;

let selectedFolderListPath = "";

const createFolderModal = document.getElementById("createFolderModal");
const createFolderInput = document.getElementById("createFolderInput");
const confirmCreateFolderBtn = document.getElementById("confirmCreateFolderBtn");
const cancelCreateFolderBtn = document.getElementById("cancelCreateFolderBtn");

const folderListBtn = document.getElementById("folderListBtn");

const folderListModal = document.getElementById("folderListModal");
const closeFolderListModalBtn = document.getElementById("closeFolderListModalBtn");
const cancelFolderListBtn = document.getElementById("cancelFolderListBtn");
const goToSelectedFolderBtn = document.getElementById("goToSelectedFolderBtn");
const selectedFolderListPathEl = document.getElementById("selectedFolderListPath");
const folderListTreeContainer = document.getElementById("folderListTreeContainer");

const expandAllFoldersBtn = document.getElementById("expandAllFoldersBtn");
const collapseAllFoldersBtn = document.getElementById("collapseAllFoldersBtn");

const expandMoveTreeBtn = document.getElementById("expandMoveTreeBtn");
const collapseMoveTreeBtn = document.getElementById("collapseMoveTreeBtn");

const deleteModal = document.getElementById("deleteModal");
const deleteTargetNameEl = document.getElementById("deleteTargetName");
const confirmDeleteBtn = document.getElementById("confirmDeleteBtn");
const cancelDeleteBtn = document.getElementById("cancelDeleteBtn");

const gallery = document.getElementById("gallery");

const currentPathEl = document.getElementById("currentPath");
currentPathEl.onclick = () => {
    currentPathEl.classList.toggle("path-expanded");
};
const upBtn = document.getElementById("upBtn");
const fileInput = document.getElementById("fileInput");
const newFolderBtn = document.getElementById("newFolderBtn");
const homeBtn = document.getElementById("homeBtn");

const viewer = document.getElementById("viewer");
const viewerBody = document.getElementById("viewerBody");
const closeViewer = document.getElementById("closeViewer");
const prevViewerBtn = document.getElementById("prevViewerBtn");
const nextViewerBtn = document.getElementById("nextViewerBtn");
const downloadViewerBtn = document.getElementById("downloadViewerBtn");
const selectViewerBtn = document.getElementById("selectViewerBtn");
const fullscreenViewerBtn = document.getElementById("fullscreenViewerBtn");

const moveModal = document.getElementById("moveModal");
const closeMoveModalBtn = document.getElementById("closeMoveModalBtn");
const cancelMoveBtn = document.getElementById("cancelMoveBtn");
const confirmMoveBtn = document.getElementById("confirmMoveBtn");
const folderTreeContainer = document.getElementById("folderTreeContainer");
const selectedMovePathEl = document.getElementById("selectedMovePath");
const moveModalTargetName = document.getElementById("moveModalTargetName");

const topbar = document.querySelector(".topbar");
const toggleTopbarBtn = document.getElementById("toggleTopbarBtn");
const topProgressBar = document.getElementById("topProgressBar");
const toggleTransfersBtn = document.getElementById("toggleTransfersBtn");
const collapseTransfersBtn = document.getElementById("collapseTransfersBtn");

function updateViewerSelectButton() {
    if (viewerIndex < 0 || viewerIndex >= viewerItems.length) return;

    const item = viewerItems[viewerIndex];
    const selected = selectedItems.has(item.relativePath);

    selectViewerBtn.textContent = selected ? "✓ Выбрано" : "Выбрать";
    selectViewerBtn.classList.toggle("selected", selected);
}

function syncSelectionToGallery(path, selected) {
    const card = cardByPath.get(path);
    if (!card) return;

    const checkbox = card.querySelector(".item-checkbox");

    card.classList.toggle("selected", selected);

    if (checkbox) {
        checkbox.checked = selected;
    }
}

function saveTransferTasks() {
    if (transferSaveTimer !== null) {
        clearTimeout(transferSaveTimer);
        transferSaveTimer = null;
    }
    const plain = Array.from(transferTasks.values()).map(task => ({
        id: task.id,
        kind: task.kind,
        name: task.name,
        status: task.status,
        progress: task.progress || 0,
        targetPath: task.targetPath || "",
        size: task.size || 0,
        uploadId: task.uploadId || null,
        totalChunks: task.totalChunks || 0,
        uploadedChunks: task.uploadedChunks || [],
        fileMeta: task.fileMeta || null,
        item: task.item || null
    }));

    localStorage.setItem(TRANSFERS_STORAGE_KEY, JSON.stringify(plain));
}

function loadTransferTasksFromStorage() {
    const raw = localStorage.getItem(TRANSFERS_STORAGE_KEY);
    if (!raw) return [];

    try {
        return JSON.parse(raw);
    } catch {
        return [];
    }
}

async function completeUploadSession(uploadId, signal) {
    const form = new URLSearchParams();
    form.append("uploadId", uploadId);

    const response = await secureFetch("/api/files/upload/complete", {
        method: "POST",
        headers: {
            "Content-Type": "application/x-www-form-urlencoded"
        },
        body: form, signal
    });

    if (!response.ok) {
        const text = await response.text();
        throw new Error("Failed to complete upload: " + text);
    }
}

async function initUploadSession(file, targetPath, chunkSize, signal) {
    const form = new URLSearchParams();
    form.append("fileName", file.name);
    form.append("fileSize", file.size);
    form.append("chunkSize", chunkSize);
    form.append("path", targetPath);
    form.append("lastModified", file.lastModified);

    const response = await secureFetch("/api/files/upload/init", {
        method: "POST",
        headers: {
            "Content-Type": "application/x-www-form-urlencoded"
        },
        body: form, signal
    });

    if (!response.ok) {
        throw new Error("Failed to init upload");
    }

    return await response.json();
}

async function getUploadStatus(uploadId, signal) {
    const response = await secureFetch(
        `/api/files/upload/status?uploadId=${encodeURIComponent(uploadId)}`, {signal}
    );

    if (!response.ok) {
        return null;
    }

    return await response.json();
}

function updateTopProgress() {
    if (!topProgressBar || !toggleTransfersBtn) return;

    const items = transferList.querySelectorAll(".progress-bar");

    if (!items.length) {
        topProgressBar.style.width = "0%";
        toggleTransfersBtn.classList.remove("loading");
        return;
    }

    toggleTransfersBtn.classList.add("loading");

    let total = 0;
    items.forEach(bar => {
        total += parseFloat(bar.style.width) || 0;
    });

    const avg = total / items.length;
    topProgressBar.style.width = avg + "%";
}

function createTransferItem(name) {
    transferPanel.classList.remove("hidden");

    const el = document.createElement("div");
    el.className = "transfer-item";

    el.innerHTML = `
        <div class="transfer-name">${escapeHtml(name)}</div>
        <div class="progress"><div class="progress-bar"></div></div>
    `;

    transferList.appendChild(el);

    return {
        el,
        bar: el.querySelector(".progress-bar")
    };
}

function addUpload(file) {
    const task = {
        id: `upload_${currentPath}_${file.name}_${file.size}_${file.lastModified}`,
        kind: "upload",
        file,
        name: file.name,
        size: file.size,
        targetPath: currentPath,
        status: "queued",
        progress: 0,
        uploadedChunks: [],
        fileMeta: {
            name: file.name,
            size: file.size,
            lastModified: file.lastModified
        }
    };

    transferTasks.set(task.id, task);
    uploadQueue.push(task);
    saveTransferTasks();
    renderTransferList();
    processUploadQueue();
}

async function processUploadQueue() {
    while (activeUploads < MAX_PARALLEL && uploadQueue.length > 0) {
        const task = uploadQueue.shift();

        if (!task.file) {
            task.status = "waiting_file";
            saveTransferTasks();
            renderTransferList();
            continue;
        }

        if (task.status === "paused" || task.status === "cancelled") {
            continue;
        }

        if (task.status === "done") continue;
        if (task.running) { uploadQueue.push(task); break; }
        runUploadTask(task);
    }
}

async function runUploadTask(task) {
    const targetPath = task.targetPath;

    activeUploads++;
    task.running = true;
    const controller = new AbortController();
    task.controller = controller;
    task.status = "uploading";

    saveTransferTasks();
    renderTransferList();

    const isPaused = () => task.status === "paused";

    try {
        await uploadFileResumableManaged(task, isPaused);

        if (task.status === "done" && currentPath === targetPath) {
            await loadFiles(currentPath);
        }
    } catch (e) {
        if (!controller.signal.aborted && e.message !== "Upload cancelled") {
            console.error("Upload error", e);
            task.status = "error";
            saveTransferTasks();
            renderTransferList();
        }
    } finally {
        task.running = false;
        delete task.controller;
        activeUploads--;

        saveTransferTasks();
        renderTransferList();

        processUploadQueue();
    }
}

async function uploadFileResumableManaged(task, isPaused) {
    const file = task.file;
    const targetPath = task.targetPath;
    const DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024;

    let initData = null;

    if (task.uploadId) {
        initData = await getUploadStatus(task.uploadId, task.controller?.signal);
    }

    if (!initData) {
        initData = await initUploadSession(file, targetPath, DEFAULT_CHUNK_SIZE, task.controller?.signal);
        task.uploadId = initData.uploadId;
    }

    task.totalChunks = initData.totalChunks;

    const uploadId = task.uploadId;
    const totalChunks = initData.totalChunks;
    const CHUNK_SIZE = Number(initData.chunkSize || 1024 * 1024);
    const uploadedChunks = new Set(initData.uploadedChunks || task.uploadedChunks || []);

    saveTransferTasks();
    for (let i = 0; i < totalChunks; i++) {
        if (cancelAllTransfers || task.status === "cancelled" || !transferTasks.has(task.id)) {
            throw new Error("Upload cancelled");
        }
        if (isPaused()) return;
        if (uploadedChunks.has(i)) {
            const percent = Math.round(((i + 1) / totalChunks) * 100);
            task.progress = percent;
            task.status = "uploading";
            queueTransferProgress(task);
            continue;
        }
        const chunk = file.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE);

        const formData = new FormData();
        formData.append("file", chunk);
        formData.append("uploadId", uploadId);
        formData.append("chunkIndex", i);

        await sendChunk(formData, task.controller?.signal);
        if (!task.uploadedChunks) {
            task.uploadedChunks = [];
        }

        if (!task.uploadedChunks.includes(i)) {
            task.uploadedChunks.push(i);
            task.uploadedChunks.sort((a, b) => a - b);
        }
        const percent = Math.round(((i + 1) / totalChunks) * 100);
        task.progress = percent;

        if (task.status !== "paused") {
            task.status = "uploading";
        }

        queueTransferProgress(task);

    }

    if (cancelAllTransfers || task.status === "cancelled" || !transferTasks.has(task.id)) {
        throw new Error("Upload cancelled");
    }
    if (isPaused()) return;
    await completeUploadSession(uploadId, task.controller?.signal);

    task.progress = 100;
    task.status = "done";
    saveTransferTasks();
    renderTransferList();
    updateTopProgress();
}

function removeTransferTask(id) {
    const task = transferTasks.get(id);
    if (!task) return;

    task.controller?.abort();
    task.status = "cancelled";

    const uploadIndex = uploadQueue.findIndex(t => t.id === id);
    if (uploadIndex >= 0) {
        uploadQueue.splice(uploadIndex, 1);
    }

    const downloadIndex = downloadQueue.findIndex(t => t.id === id);
    if (downloadIndex >= 0) {
        downloadQueue.splice(downloadIndex, 1);
    }

    transferTasks.delete(id);
    saveTransferTasks();
    renderTransferList();
    updateTopProgress();
}

function getStatusText(status) {
    if (status === "browser") return "Передано браузеру";
    switch (status) {
        case "queued":
            return "в очереди";
        case "uploading":
            return "загружается";
        case "paused":
            return "пауза";
        case "done":
            return "завершено";
        case "error":
            return "ошибка";
        case "cancelled":
            return "отменено";
        case "waiting_file":
            return "нужно выбрать файл снова";
        default:
            return status || "";
    }
}

function renderTransferList() {
    transferList.innerHTML = "";

    const tasks = Array.from(transferTasks.values());

    if (!tasks.length) {
        updateTopProgress();
        return;
    }

    transferPanel.classList.remove("hidden");

    for (const task of tasks) {
        const el = document.createElement("div");
        el.className = "transfer-item";
        el.dataset.id = task.id;
        const statusText = getStatusText(task.status);

        el.innerHTML = `
            <div class="transfer-row">
               <div class="transfer-name"><span>${escapeHtml(task.name)}</span></div>
                <div class="transfer-status">${statusText}</div>
                <button class="transfer-remove" data-id="${escapeHtml(task.id)}">✕</button>
            </div>

            <div class="progress">
                <div class="progress-bar" style="width:${task.progress || 0}%"></div>
            </div>

            <div class="transfer-actions">
    <div class="transfer-size">
        ${formatFileSize(task.fileMeta?.size || task.size)}
    </div>

    ${
            task.status === "done" || task.status === "browser" || task.status === "error"
                ? ""
                : `<button class="control ${task.status === "paused" ? "paused" : "playing"}" data-id="${escapeHtml(task.id)}">
                    ${task.status === "paused" ? "⏸" : "▶"}
               </button>`
        }
</div>
        `;

        transferList.appendChild(el);
    }

    updateTopProgress();
    applyMarqueeIfNeeded();
}

function updateTransferTaskUI(task) {
    const item = transferList.querySelector(`.transfer-item[data-id="${CSS.escape(task.id)}"]`);
    if (!item) return;

    const bar = item.querySelector(".progress-bar");
    const status = item.querySelector(".transfer-status");
    const btn = item.querySelector(".control");

    if (bar) bar.style.width = (task.progress || 0) + "%";
    if (status) status.textContent = getStatusText(task.status);

    if (btn) {
        btn.textContent = task.status === "paused" ? "⏸" : "▶";
        btn.classList.toggle("paused", task.status === "paused");
        btn.classList.toggle("playing", task.status !== "paused");
    }

    updateTopProgress();
}

function addDownload(item) {
    const task = {
        id: `download_${item.relativePath || item.name}_${Date.now()}`,
        kind: "download",
        name: item.name,
        status: "queued",
        progress: 0,
        item
    };

    transferTasks.set(task.id, task);
    downloadQueue.push(task);
    saveTransferTasks();
    renderTransferList();
    processDownloadQueue();
}

async function processDownloadQueue() {
    if (activeDownloads >= MAX_PARALLEL) return;
    if (!downloadQueue.length) return;

    const task = downloadQueue.shift();
    if (task.status === "paused" || task.status === "cancelled" || !transferTasks.has(task.id)) {
        return processDownloadQueue();
    }
    if (task.running) { downloadQueue.push(task); return; }
    activeDownloads++;
    task.running = true;

    task.status = "uploading";
    saveTransferTasks();
    renderTransferList();

    try {
        await downloadResumableManaged(task);
    } catch (e) {
        if (e.message !== "Download cancelled") {
            console.error("Download error", e);
            task.status = "error";
            saveTransferTasks();
            renderTransferList();
        }
    }

    task.running = false;
    activeDownloads--;
    processDownloadQueue();
}

document.getElementById("clearTransfersBtn")?.addEventListener("click", async () => {
    cancelAllTransfers = true;

    uploadQueue.length = 0;
    downloadQueue.length = 0;
    for (const task of transferTasks.values()) task.controller?.abort();
    transferTasks.clear();

    renderTransferList();
    transferPanel.classList.add("hidden");
    localStorage.removeItem(TRANSFERS_STORAGE_KEY);

    try {
        await secureFetch("/api/files/clear-temp", {
            method: "DELETE"
        });
    } catch (e) {
        console.error("Ошибка очистки temp:", e);
    }

    setTimeout(() => {
        cancelAllTransfers = false;
    }, 300);

});

function applyMarqueeIfNeeded() {
    document.querySelectorAll(".transfer-name").forEach(el => {
        const span = el.querySelector("span");
        if (!span) return;

        // если текст шире контейнера — включаем бегущую строку
        if (span.scrollWidth > el.clientWidth) {
            el.classList.add("marquee");
        } else {
            el.classList.remove("marquee");
        }
    });
}

function restoreTransferTasks() {
    const saved = loadTransferTasksFromStorage();

    for (const task of saved) {
        if (task.kind === "upload") {
            task.file = null;

            if (task.status !== "done") {
                task.status = "waiting_file";
            }
        }

        if (task.kind === "download") {
            if (task.status !== "done" && task.status !== "browser") {
                task.status = "paused";
            }
        }

        transferTasks.set(task.id, task);
    }

    renderTransferList();
}

async function downloadResumableManaged(task) {
    const item = task.item;
    const cancelled = () => cancelAllTransfers || task.status === "cancelled" || !transferTasks.has(task.id);
    if (cancelled()) throw new Error("Download cancelled");
    if (task.status === "paused") return;
    // Large downloads are streamed to disk by the browser, without a second fetch or Blob.
    if (item.type === "video" || !Number.isFinite(item.size) || item.size > 50 * 1024 * 1024) {
        triggerBrowserDownload(shareApi(item.downloadUrl), item.name);
        task.status = "browser";
        saveTransferTasks();
        renderTransferList();
        return;
    }
    const controller = new AbortController();
    task.controller = controller;
    let reader;
    try {
        // Restart from zero: no persisted file bytes exist to support Range resume.
        const response = await secureFetch(item.downloadUrl, {signal: controller.signal});
        if (!response.ok || response.status === 206) throw new Error("Download failed");
        if (!response.body) throw new Error("ReadableStream is not available");
        reader = response.body.getReader();
        const chunks = [];
        let received = 0;
        const total = Number(response.headers.get("Content-Length")) || item.size;
        while (true) {
            if (cancelled()) throw new Error("Download cancelled");
            const {done, value} = await reader.read();
            if (cancelled()) throw new Error("Download cancelled");
            if (done) break;
            received += value.byteLength;
            if (received > 50 * 1024 * 1024) throw new Error("File is too large for in-app download");
            chunks.push(value);
            task.progress = total > 0 ? Math.min(100, Math.round(received / total * 100)) : 0;
            queueTransferProgress(task);
        }
        const objectUrl = URL.createObjectURL(new Blob(chunks));
        triggerBrowserDownload(objectUrl, item.name);
        setTimeout(() => URL.revokeObjectURL(objectUrl), 5000);
        task.progress = 100;
        task.status = "done";
        saveTransferTasks();
        renderTransferList();
    } catch (error) {
        if (task.status === "paused" || (task.status === "queued" && controller.signal.aborted)) return;
        if (cancelled() || controller.signal.aborted) throw new Error("Download cancelled");
        throw error;
    } finally {
        controller.abort();
        if (reader) {
            await reader.cancel().catch(() => {});
            reader.releaseLock();
        }
        delete task.controller;
    }
}

function setTopbarCollapsed(collapsed) {
    if (!topbar || !toggleTopbarBtn) return;

    topbar.classList.toggle("collapsed", collapsed);
    document.body.classList.toggle("topbar-collapsed", collapsed);
    toggleTopbarBtn.textContent = collapsed ? "▾" : "▴";

    localStorage.setItem("topbarCollapsed", collapsed ? "1" : "0");
}

function toggleTopbar() {
    const collapsed = !topbar.classList.contains("collapsed");
    setTopbarCollapsed(collapsed);
}

async function openFolderListModal() {
    selectedFolderListPath = currentPath || "";
    selectedFolderListPathEl.textContent =
        `Выбрано: ${selectedFolderListPath ? "/" + selectedFolderListPath : "/"}`;

    folderListTreeContainer.innerHTML = `<div>Загрузка папок...</div>`;
    folderListModal.classList.remove("hidden");

    const response = await secureFetch("/api/files/folders/tree");
    if (!response.ok) {
        folderListTreeContainer.innerHTML = `<div>Не удалось загрузить список папок</div>`;
        return;
    }

    const tree = await response.json();
    folderListTreeContainer.innerHTML = "";
    folderListTreeContainer.appendChild(renderFolderListTree(tree));
}

function renderFolderListTree(node) {
    const wrapper = document.createElement("div");
    wrapper.className = "folder-tree-node";

    const row = document.createElement("div");
    row.className = "folder-tree-row";

    const toggle = document.createElement("div");
    toggle.className = "folder-toggle";

    const hasChildren = node.children && node.children.length > 0;
    toggle.textContent = hasChildren ? "▼" : "";

    const label = document.createElement("div");
    label.className = "folder-label";
    label.textContent = node.name === "/" ? "📁 Корень /" : `📁 ${node.name}`;

    row.appendChild(toggle);
    row.appendChild(label);

    // Подсветить текущую выбранную папку при открытии
    if ((node.relativePath || "") === (selectedFolderListPath || "")) {
        row.classList.add("selected");
    }

    row.addEventListener("click", () => {
        selectedFolderListPath = node.relativePath || "";
        selectedFolderListPathEl.textContent =
            `Выбрано: ${selectedFolderListPath ? "/" + selectedFolderListPath : "/"}`;

        folderListTreeContainer.querySelectorAll(".folder-tree-row.selected").forEach(el => {
            el.classList.remove("selected");
        });

        row.classList.add("selected");
        console.log("Выбрана папка:", selectedFolderListPath);
    });

    wrapper.appendChild(row);
    if (hasChildren) {
        const childrenContainer = document.createElement("div");
        childrenContainer.className = "folder-children hidden"; // <-- сразу скрыто
        toggle.textContent = "▶"; // <-- стрелка вправо

        for (const child of node.children) {
            childrenContainer.appendChild(renderFolderListTree(child));
        }

        toggle.addEventListener("click", (e) => {
            e.stopPropagation();
            childrenContainer.classList.toggle("hidden");
            toggle.textContent = childrenContainer.classList.contains("hidden") ? "▶" : "▼";
        });

        wrapper.appendChild(childrenContainer);
    }
    return wrapper;
}

async function cleanupCurrentPreview() {
    previewWaitController?.abort();
    if (currentPreviewId) {
        const id = currentPreviewId;
        currentPreviewId = null;

        try {
            await secureFetch(`/api/files/preview/cancel?previewId=${encodeURIComponent(id)}`, {
                method: "DELETE"
            });
        } catch (e) {
            console.error("Preview cleanup failed", e);
        }
    }

}

async function openRenamePreview(item) {
    const form = new URLSearchParams();
    form.append("path", item.relativePath);

    const resp = await secureFetch("/api/files/preview/rename-original-start", {
        method: "POST",
        headers: {
            "Content-Type": "application/x-www-form-urlencoded"
        },
        body: form
    });

    if (!resp.ok) {
        alert("Ошибка подготовки файла для просмотра");
        return null;
    }

    const data = await resp.json();
    currentPreviewId = data.previewId;

    return waitForPreview(currentPreviewId, 500);

}

function expandAllTreeNodes(container) {
    container.querySelectorAll(".folder-children").forEach(el => {
        el.classList.remove("hidden");
    });

    container.querySelectorAll(".folder-toggle").forEach(el => {
        if (el.textContent.trim() !== "") {
            el.textContent = "▼";
        }
    });
}

function collapseAllTreeNodes(container) {
    container.querySelectorAll(".folder-children").forEach(el => {
        el.classList.add("hidden");
    });

    container.querySelectorAll(".folder-toggle").forEach(el => {
        if (el.textContent.trim() !== "") {
            el.textContent = "▶";
        }
    });
}

function closeFolderListModal() {
    folderListModal.classList.add("hidden");
    folderListTreeContainer.innerHTML = "";
    selectedFolderListPath = "";
}

async function goToSelectedFolder() {
    thumbSession++;
    folderLoadSession++;

    hideMetadataLoadingModal();
    hideThumbLoadingModal();
    hideFolderLoadingRing();
    const targetPath = selectedFolderListPath || "";
    console.log("Переходим в:", targetPath);

    if (targetPath === (currentPath || "")) {
        closeFolderListModal();
        return;
    }

    closeFolderListModal();
    await loadFiles(targetPath);
}

function enablePreviewPan(img) {
    let isDragging = false;
    let moved = false;

    let startX = 0;
    let startY = 0;

    let posX = 50;
    let posY = 50;

    function setPosition(x, y) {
        img.style.objectPosition = `${x}% ${y}%`;
    }

    function resetPosition() {
        img.style.objectPosition = "center";
        posX = 50;
        posY = 50;
        img.classList.remove("dragging-thumb");
    }

    function update(clientX, clientY) {
        const dx = clientX - startX;
        const dy = clientY - startY;

        if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
            moved = true;
        }

        posX = Math.max(0, Math.min(100, posX + dx * 0.18));
        posY = Math.max(0, Math.min(100, posY + dy * 0.18));

        setPosition(posX, posY);

        startX = clientX;
        startY = clientY;
    }

    function startDrag(clientX, clientY) {
        isDragging = true;
        moved = false;
        startX = clientX;
        startY = clientY;
        img.classList.add("dragging-thumb");
    }

    function endDrag() {
        if (!isDragging) return;

        isDragging = false;
        setTimeout(() => {
            resetPosition();
            moved = false; // важно: сбрасываем блокировку клика
        }, 40);
    }

    img.addEventListener("mousedown", (e) => {
        e.preventDefault();

        const moveHandler = (e) => update(e.clientX, e.clientY);
        const upHandler = () => {
            endDrag();
            window.removeEventListener("mousemove", moveHandler);
            window.removeEventListener("mouseup", upHandler);
        };

        startDrag(e.clientX, e.clientY);

        const signal = folderLoadAbortController?.signal;
        window.addEventListener("mousemove", moveHandler, {signal});
        window.addEventListener("mouseup", upHandler, {signal});
    });

    img.addEventListener("touchstart", (e) => {
        const t = e.touches[0];
        if (!t) return;

        startDrag(t.clientX, t.clientY);
    }, {passive: true});

    img.addEventListener("touchmove", (e) => {
        if (!isDragging) return;
        const t = e.touches[0];
        if (!t) return;

        e.preventDefault();
        update(t.clientX, t.clientY);
    }, {passive: false});

    img.addEventListener("touchend", () => {
        endDrag();
    });

    img.addEventListener("touchcancel", () => {
        endDrag();
    });

    img.addEventListener("click", (e) => {
        if (moved) {
            e.preventDefault();
            e.stopPropagation();
        }
    });
}

function appendItems(items) {
    if (activeFolderPath !== currentPath || !items?.length) return;
    const session = folderLoadSession;
    currentItems.push(...items);
    viewerItems.push(...items.filter(item => !item.directory && (item.type === "image" || item.type === "video")));
    const fragment = document.createDocumentFragment();
    const cards = [];
    const images = [];
    for (const item of items) {
        const card = createCard(item);
        cardByPath.set(item.relativePath, card);
        cards.push(card);
        card.querySelectorAll("img.lazy-thumb[data-src]").forEach(img => {
            images.push(img);
            img.dataset.panEnabled = "1";
            enablePreviewPan(img);
        });
        fragment.appendChild(card);
    }
    gallery.appendChild(fragment);
    requestAnimationFrame(() => {
        if (session !== folderLoadSession) return;
        initLazyThumbs(images);
        initLazyMetadata(cards);
        updateBulkButtons();
        if (PUBLIC_SHARE_MODE) updatePublicShareButtons(window.PUBLIC_SHARE_PERMISSION);
    });
}

const metadataLoadingModal = document.getElementById("metadataLoadingModal");
const metadataLoadingBar = document.getElementById("metadataLoadingBar");
const metadataLoadingCount = document.getElementById("metadataLoadingCount");
const metadataLoadingText = document.getElementById("metadataLoadingText");

function showMetadataLoadingModal() {
    if (!metadataLoadingModal || !metadataLoadingBar || !metadataLoadingCount) {
        console.warn("Metadata loading modal not found");
        return;
    }
    metadataLoadingModal.classList.remove("hidden");
    metadataLoadingBar.style.width = "0%";
    metadataLoadingCount.textContent = "0 из 0 файлов";
}

function updateMetadataLoadingModal(progress, processed, total, stage = "Подготовка папки") {
    metadataLoadingBar.style.width = `${progress || 0}%`;
    metadataLoadingCount.textContent = `${processed || 0} из ${total || 0} файлов`;
    if (metadataLoadingTitle) {
        metadataLoadingTitle.textContent = stage;
    }

    if (metadataLoadingText) {
        metadataLoadingText.textContent = `${processed} / ${total}`;
    }
}

function hideMetadataLoadingModal() {
    if (!metadataLoadingModal) return;
    metadataLoadingModal.classList.add("hidden");
}

function showThumbLoadingModal(total) {
    if (!thumbLoadingModal || !thumbLoadingBar || !thumbLoadingCount) {
        console.warn("Thumb loading modal not found");
        return;
    }

    clearTimeout(thumbLoadingHideTimer);

    thumbLoadingTotal = total || 0;
    thumbLoadingDone = 0;

    if (thumbLoadingTitle) {
        thumbLoadingTitle.textContent = "Загрузка миниатюр";
    }

    if (thumbLoadingText) {
        thumbLoadingText.textContent = "Загружаем постеры карточек...";
    }

    thumbLoadingBar.style.width = "0%";
    thumbLoadingCount.textContent = `0 из ${thumbLoadingTotal} постеров`;

    thumbLoadingModal.classList.remove("hidden");
}

function updateThumbLoadingModal(done, total) {
    if (!thumbLoadingModal || !thumbLoadingBar || !thumbLoadingCount) return;

    const safeTotal = total || 0;
    const safeDone = Math.min(done || 0, safeTotal);

    const percent = safeTotal > 0
        ? Math.round((safeDone * 100) / safeTotal)
        : 100;

    thumbLoadingBar.style.width = `${percent}%`;
    thumbLoadingCount.textContent = `${safeDone} из ${safeTotal} постеров`;

    if (thumbLoadingText) {
        thumbLoadingText.textContent = `Загружено: ${percent}%`;
    }
}

function hideThumbLoadingModal() {
    if (!thumbLoadingModal) return;
    thumbLoadingModal.classList.add("hidden");
}

function getGalleryColumns() {
    const grid = getComputedStyle(gallery);
    const columns = grid.gridTemplateColumns.split(" ").filter(Boolean).length;
    return Math.max(1, columns);
}

function updateGalleryScrollMode() {
    if (!gallery) return;

    const total = estimatedTotalItems || currentItems.length;
    const needScroll = total > 20;

    gallery.classList.toggle("gallery-scroll", needScroll);
}

function showFolderLoadingRing(percent = 0) {
    const ring = document.getElementById("folderLoadingRing");
    if (!ring) return;

    clearTimeout(folderRingHideTimer);

    ring.classList.remove("hidden");
    updateFolderLoadingRing(percent);
}

function updateFolderLoadingRing(percent) {
    const progress = document.getElementById("folderLoadingRingProgress");
    const text = document.getElementById("folderLoadingPercent");

    if (!progress || !text) return;

    const safePercent = Math.max(0, Math.min(100, percent));

    progress.setAttribute("stroke-dasharray", `${safePercent}, 100`);
    text.textContent = `${safePercent}%`;
}

function hideFolderLoadingRing() {
    const ring = document.getElementById("folderLoadingRing");
    if (!ring) return;

    clearTimeout(folderRingHideTimer);

    folderRingHideTimer = setTimeout(() => {
        ring.classList.add("hidden");
    }, 400);
}

async function confirmDelete() {
    showLoadingGif();

    try {
        if (selectedItems.size > 0 && deleteTargetPath == null) {
            for (const item of selectedItems.values()) {
                const response = await secureFetch(`/api/files?path=${encodeURIComponent(item.path)}`, {
                    method: "DELETE"
                });

                if (!response.ok) {
                    const text = await response.text();
                    alert(`Ошибка удаления "${item.name}":\n` + text);
                    return;
                }
            }

            selectedItems.clear();
            closeDeleteModal();
            await loadFiles(currentPath);
            updateBulkButtons();
            return;
        }

        if (!deleteTargetPath) return;

        const response = await secureFetch(`/api/files?path=${encodeURIComponent(deleteTargetPath)}`, {
            method: "DELETE"
        });

        if (!response.ok) {
            const text = await response.text();
            alert("Ошибка удаления:\n" + text);
            return;
        }

        closeDeleteModal();
        await loadFiles(currentPath);
    } finally {
        hideLoadingGif();
    }
}

function getVisibleItems() {

    const cards = document.querySelectorAll(".card[data-path]");
    const visible = [];

    cards.forEach(card => {
        const rect = card.getBoundingClientRect();

        if (rect.top < window.innerHeight && rect.bottom > 0) {
            const path = card.dataset.path;
            if (path && !metadataLoaded.has(path)) {
                visible.push(path);
            }
        }
    });

    return visible;
}

function loadVisibleMetadata() {
    const cards = document.querySelectorAll(".card");

    const visible = [];

    cards.forEach(card => {
        const rect = card.getBoundingClientRect();

        if (rect.top < window.innerHeight + 200) {
            const path = card.dataset.path;

            if (!metadataLoaded.has(path)) {
                visible.push(path);
            }
        }
    });

    if (visible.length) {
        enqueueMetadata(visible);
    }
}

function closeDeleteModal() {
    deleteModal.classList.add("hidden");
    deleteTargetPath = null;
    deleteTargetName = null;
}

function openDeleteModal(path, name) {
    deleteTargetPath = path;
    deleteTargetName = name;

    deleteTargetNameEl.textContent = name;
    deleteModal.classList.remove("hidden");
}

function createCard(item) {
    const card = document.createElement("div");
    card.className = "card";
    card.dataset.path = item.relativePath;

    if (!PUBLIC_SHARE_MODE && sharedLinksByPath.has(item.relativePath)) {
        const shareBadge = document.createElement("button");
        shareBadge.className = "card-share-badge";
        shareBadge.title = "Общий доступ";
        shareBadge.textContent = "↗";

        shareBadge.onclick = (e) => {
            e.preventDefault();
            e.stopPropagation();
            openShareModal(item);
        };

        card.appendChild(shareBadge);
    }

    const selectBox = document.createElement("input");
    selectBox.type = "checkbox";
    selectBox.className = "item-checkbox";
    selectBox.checked = selectedItems.has(item.relativePath);

    card.classList.toggle("selected", selectedItems.has(item.relativePath));
    const thumb = document.createElement("div");
    thumb.className = "thumb";

    if (item.directory) {
        thumb.innerHTML = `<div class="folder-thumb">📁</div>`;

        thumb.addEventListener("click", () => {
            if (groupingMode === "shared") {
                resetGroupingState();
            }

            loadFiles(item.relativePath);
        });
    } else if (item.type === "image") {
        thumb.innerHTML = `
    <img
        src="/image-placeholder.png"
        loading="lazy"

        data-src="${item.thumbnailUrl || buildImageThumbnailUrl(item.relativePath)}"
        alt="${escapeHtml(item.name)}"
        class="lazy-thumb image-thumb"
    >
`;
        thumb.addEventListener("click", () => openViewerByPath(item.relativePath));
    } else if (item.type === "video") {
        thumb.innerHTML = `
    <div class="video-thumb-wrap">
        <img
            src="/video-placeholder.png"
            loading="lazy"
            data-src="${buildVideoThumbnailUrl(item.relativePath)}"
            alt="${escapeHtml(item.name)}"
            class="lazy-thumb video-thumb-img"
        >
        <div class="play-badge">▶</div>
    </div>
`;
        thumb.addEventListener("click", () => openViewerByPath(item.relativePath));
    } else {
        thumb.innerHTML = `<div class="file-thumb">📄</div>`;
    }

    const body = document.createElement("div");
    body.className = "card-body";

    let sizeText;

    if (item.directory) {
        const fileCount = item.fileCount ?? 0;
        const folderCount = item.folderCount ?? 0;

        const hasCounts = item.fileCount !== null && item.fileCount !== undefined
            || item.folderCount !== null && item.folderCount !== undefined;

        if (hasCounts) {
            sizeText = (fileCount > 0 || folderCount > 0)
                ? "Папка с файлами"
                : "Пустая папка";
        } else {
            sizeText = "Папка";
        }
    } else {
        sizeText = formatBytes(item.size);
    }

    const dateText = !item.directory && item.createdAt
        ? " · " + formatDateTime(item.createdAt)
        : "";
    body.innerHTML = `
    <div class="file-name">${escapeHtml(item.name)}</div>
    <div class="meta-row">
        <label class="select-line">
            <span class="meta ${item.directory ? 'folder-meta' : ''}" data-size="${escapeHtml(sizeText)}">
    ${escapeHtml(sizeText)}
               <span class="card-created-date" data-path="${escapeHtml(item.relativePath)}">${dateText}</span>
            </span>
        </label>
    </div>
`;

    if (item.searchDescription !== undefined) {
        card.classList.add("photo-search-result");
        const folderBtn = document.createElement("button");
        folderBtn.type = "button";
        folderBtn.className = "card-properties-btn card-location-btn";
        folderBtn.title = "Открыть папку файла";
        folderBtn.setAttribute("aria-label", folderBtn.title);
        folderBtn.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M3 7V5h6l2 2h10v13H3Z"/></svg>`;
        folderBtn.onclick = event => {
            event.preventDefault();
            event.stopPropagation();
            const separator = item.relativePath.lastIndexOf("/");
            resetGroupingState();
            loadFiles(separator < 0 ? "" : item.relativePath.slice(0, separator));
        };
        card.appendChild(folderBtn);
    }

    card.appendChild(selectBox);
    selectBox.addEventListener("click", (e) => {
        e.stopPropagation();

        if (selectBox.checked) {
            selectedItems.set(item.relativePath, {
                path: item.relativePath,
                name: item.name,
                directory: item.directory
            });
        } else {
            selectedItems.delete(item.relativePath);
        }

        card.classList.toggle("selected", selectBox.checked);
        updateBulkButtons();
    });

    const actions = document.createElement("div");
    actions.className = "card-actions";

    const canPublicDownload =
        !PUBLIC_SHARE_MODE || !!item.downloadUrl;

    const canPublicDelete =
        !PUBLIC_SHARE_MODE;

    if (item.directory) {

        const openBtn = document.createElement("button");
        openBtn.className = "card-open-btn";
        openBtn.title = "Открыть";

        openBtn.innerHTML = `
<svg viewBox="0 0 24 24"
     fill="none"
     stroke="currentColor"
     stroke-width="2"
     stroke-linecap="round"
     stroke-linejoin="round">

    <path d="M5 12h14"/>
    <path d="M13 5l7 7-7 7"/>
</svg>
`;
        openBtn.onclick = () => {
            if (groupingMode === "shared") {
                resetGroupingState();
            }

            loadFiles(item.relativePath);
        };

        card.appendChild(openBtn);
    } else {
        const lower = item.name.toLowerCase();

        if (lower.endsWith(".insv") || lower.endsWith(".lrv")) {

            const downloadBtn = document.createElement("button");
            downloadBtn.className = "card-download-btn";
            downloadBtn.title = "Скачать";

            downloadBtn.innerHTML = `
<svg viewBox="0 0 24 24"
     fill="none"
     stroke="currentColor"
     stroke-width="2"
     stroke-linecap="round"
     stroke-linejoin="round">

    <path d="M12 3v11"/>
    <path d="M8 10l4 4 4-4"/>
    <path d="M4 20h16"/>

</svg>
`;

            downloadBtn.onclick = (e) => {
                e.preventDefault();
                e.stopPropagation();
                openDownloadFormatModal(item);
            };

            card.appendChild(downloadBtn);
        } else {
            if (item.downloadUrl) {

                const downloadLink = document.createElement("a");

                downloadLink.className = "card-download-btn";
                downloadLink.title = "Скачать";

                downloadLink.href = item.downloadUrl;
                downloadLink.setAttribute("download", item.name);

                downloadLink.innerHTML = `
<svg viewBox="0 0 24 24"
     fill="none"
     stroke="currentColor"
     stroke-width="2"
     stroke-linecap="round"
     stroke-linejoin="round">

    <path d="M12 3v11"/>
    <path d="M8 10l4 4 4-4"/>
    <path d="M4 20h16"/>

</svg>
`;

                card.appendChild(downloadLink);
            }
        }
    }

    if (!PUBLIC_SHARE_MODE || window.PUBLIC_SHARE_PERMISSION === "MANAGE") {
        const deleteBtn = document.createElement("button");
        deleteBtn.className = "card-delete-btn";
        deleteBtn.title = "Удалить";
        deleteBtn.textContent = "🗑";

        deleteBtn.onclick = (e) => {
            e.preventDefault();
            e.stopPropagation();
            openDeleteModal(item.relativePath, item.name);
        };

        card.appendChild(deleteBtn);
    }

    if (!PUBLIC_SHARE_MODE) {

        const shareBtn = document.createElement("button");

        shareBtn.className = "card-share-btn";
        shareBtn.title = "Общий доступ";
        shareBtn.innerHTML = `
<svg viewBox="0 0 24 24"
     width="18"
     height="18"
     fill="none"
     stroke="currentColor"
     stroke-width="2"
     stroke-linecap="round"
     stroke-linejoin="round">

    <circle cx="18" cy="5" r="3"></circle>
    <circle cx="6" cy="12" r="3"></circle>
    <circle cx="18" cy="19" r="3"></circle>

    <path d="M8.7 10.8L15.3 6.2"></path>
    <path d="M8.7 13.2L15.3 17.8"></path>

</svg>
`;
        shareBtn.onclick = (e) => {
            e.preventDefault();
            e.stopPropagation();
            openShareModal(item);
        };

        card.appendChild(shareBtn);
    }

    if (!PUBLIC_SHARE_MODE) {

        const propertiesBtn = document.createElement("button");
        propertiesBtn.className = "card-properties-btn";
        propertiesBtn.title = "Свойства";
        propertiesBtn.innerHTML = `
<svg viewBox="0 0 24 24"
     fill="none"
     stroke="currentColor"
     stroke-width="2"
     stroke-linecap="round">
    <line x1="5" y1="7" x2="19" y2="7"></line>
    <line x1="5" y1="12" x2="19" y2="12"></line>
    <line x1="5" y1="17" x2="19" y2="17"></line>
</svg>
`;

        propertiesBtn.onclick = async (e) => {
            e.preventDefault();
            e.stopPropagation();

            currentPropertiesPath = item.relativePath;
            currentPropertiesName = item.name;

            await openPropertiesModal(item.relativePath);
        };

        card.appendChild(propertiesBtn);
    }

    body.appendChild(actions);
    card.appendChild(thumb);
    card.appendChild(body);

    return card;
}

function getSelectedSharePermission() {
    const checked = document.querySelector('input[name="sharePermission"]:checked');
    return checked ? checked.value : "VIEW";
}

async function openShareModal(item) {
    shareTargetItem = item;
    currentShareToken = null;
    shareTargetLinks = [];

    shareTargetName.textContent =
        item.directory
            ? `Папка: ${item.name}`
            : `Файл: ${item.name}`;

    shareExpiresSelect.value = "";
    shareUrlInput.value = "";
    shareResultBox.classList.add("hidden");

    document.querySelectorAll('input[name="sharePermission"]').forEach(input => {
        input.checked = input.value === "VIEW";
    });

    shareModal.classList.remove("hidden");
    updateSharePermissionOptions(item);

    shareTargetLinks = await loadExistingShareForItem(item);
    renderExistingShareLinks();
}

function updateSharePermissionOptions(item) {
    const uploadRow = document.querySelector('input[name="sharePermission"][value="UPLOAD"]')?.closest(".share-permission-row");
    const manageRow = document.querySelector('input[name="sharePermission"][value="MANAGE"]')?.closest(".share-permission-row");

    if (!item.directory) {
        uploadRow?.classList.add("hidden");
        manageRow?.classList.add("hidden");

        const checked = document.querySelector('input[name="sharePermission"]:checked');

        if (checked && (checked.value === "UPLOAD" || checked.value === "MANAGE")) {
            document.querySelector('input[name="sharePermission"][value="VIEW"]').checked = true;
        }
    } else {
        uploadRow?.classList.remove("hidden");
        manageRow?.classList.remove("hidden");
    }
}

function closeShareModal() {
    shareModal.classList.add("hidden");

    shareTargetItem = null;
    currentShareToken = null;

    shareUrlInput.value = "";
    shareResultBox.classList.add("hidden");
}

async function createShareLink() {
    if (!shareTargetItem) return;

    const permission = getSelectedSharePermission();

    const duplicate = shareTargetLinks.find(link =>
        link.permission === permission
    );

    if (duplicate) {
        showDuplicateShareModal(duplicate);
        return;
    }

    const expiresRaw = shareExpiresSelect.value;

    const payload = {
        path: shareTargetItem.relativePath,
        permission,
        expiresInDays: expiresRaw ? Number(expiresRaw) : null
    };

    const response = await secureFetch("/api/share", {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify(payload)
    });

    if (!response.ok) {
        const text = await response.text();
        alert("Не удалось создать ссылку:\n" + text);
        return;
    }

    const data = await response.json();

    const alreadyInList = shareTargetLinks.some(link =>
        link.permission === data.permission
    );

    if (alreadyInList) {
        showDuplicateShareModal(data);
        return;
    }

    shareTargetLinks.push(data);
    renderExistingShareLinks();

    currentShareToken = data.token;
    shareUrlInput.value = data.url;
    shareResultBox.classList.remove("hidden");

    showToast("Ссылка создана");
    await loadSharedLinksIndex();
    await loadFiles(currentPath, {showPrepareModal: false});
}

async function deleteShareLinkByToken(token) {
    if (!token) return;

    if (!confirm("Удалить ссылку общего доступа?")) {
        return;
    }

    const response = await secureFetch(
        `/api/share/${encodeURIComponent(token)}`,
        {method: "DELETE"}
    );

    if (!response.ok) {
        alert("Не удалось удалить ссылку");
        return;
    }

    shareTargetLinks = shareTargetLinks.filter(link => link.token !== token);
    renderExistingShareLinks();

    if (currentShareToken === token) {
        currentShareToken = null;
        shareUrlInput.value = "";
        shareResultBox.classList.add("hidden");
    }

    showToast("Ссылка удалена");
    await loadSharedLinksIndex();
    await loadFiles(currentPath, {showPrepareModal: false});
}

function showDuplicateShareModal(link) {
    duplicateShareUrlInput.value = link.url;
    shareDuplicateModal.classList.remove("hidden");
}

function closeDuplicateShareModal() {
    shareDuplicateModal.classList.add("hidden");
    duplicateShareUrlInput.value = "";
}

copyDuplicateShareBtn?.addEventListener("click", async () => {
    await copyText(duplicateShareUrlInput.value);
    showToast("ссылка скопирована");
});

closeDuplicateShareBtn?.addEventListener("click", closeDuplicateShareModal);
closeDuplicateShareModalBtn?.addEventListener("click", closeDuplicateShareModal);

async function disableCurrentShareLink() {
    await deleteShareLinkByToken(currentShareToken);
}

async function copyCurrentShareLink() {
    const url = shareUrlInput.value;
    if (!url) return;

    try {
        await navigator.clipboard.writeText(url);
    } catch (e) {
        shareUrlInput.focus();
        shareUrlInput.select();
        document.execCommand("copy");
    }

    showCopyShareToast();
}

function showCopyShareToast() {
    const toast = document.getElementById("toast");

    if (!toast) {
        alert("ссылка скопирована");
        return;
    }

    toast.textContent = "ссылка скопирована";
    toast.classList.remove("hidden");

    requestAnimationFrame(() => {
        toast.classList.add("show");
    });

    setTimeout(() => {
        toast.classList.remove("show");

        setTimeout(() => {
            toast.classList.add("hidden");
        }, 250);

    }, 2000);
}

function buildImageThumbnailUrl(path) {
    if (!path) return "/image-placeholder.png";
    if (PUBLIC_SHARE_MODE) {
        return `/share/${SHARE_TOKEN}/thumbnail?path=${encodeURIComponent(path)}`;
    }
    return `/api/files/image-thumbnail?path=${encodeURIComponent(path)}`;
}

function buildVideoStreamUrl(path) {
    if (PUBLIC_SHARE_MODE) {
        return `/share/${SHARE_TOKEN}/stream?path=${encodeURIComponent(path)}`;
    }
    return `/api/files/stream?path=${encodeURIComponent(path)}`;
}

function buildVideoThumbnailUrl(path) {
    if (!path) return "/video-placeholder.png";
    if (PUBLIC_SHARE_MODE) {
        return `/share/${SHARE_TOKEN}/thumbnail?path=${encodeURIComponent(path)}`;
    }
    return `/api/files/video-thumbnail?path=${encodeURIComponent(path)}`;
}

function renderItems(items) {
    const copy = [...(items || [])];
    window.thumbObserver?.disconnect();
    window.metadataObserver?.disconnect();
    window.thumbObserver = window.metadataObserver = null;
    thumbSession++;
    cardByPath.clear();
    gallery.innerHTML = "";
    currentItems = [];
    viewerItems = [];
    if (!copy.length) {
        gallery.textContent = "Папка пуста";
        return;
    }
    appendItems(copy);
}

async function openPropertiesModal(path) {
    propertiesModal.classList.remove("hidden");
    propertiesBody.innerHTML = "Загрузка свойств...";
    const descriptionBtn = document.getElementById("propertiesDescriptionBtn");
    const descriptionBody = document.getElementById("propertiesDescription");
    const description = currentItems.find(item => item.relativePath === path)?.searchDescription;
    descriptionBody.textContent = description || "";
    descriptionBody.hidden = true;
    descriptionBtn.hidden = !description;
    descriptionBtn.setAttribute("aria-expanded", "false");
    descriptionBtn.onclick = () => {
        descriptionBody.hidden = !descriptionBody.hidden;
        descriptionBtn.setAttribute("aria-expanded", String(!descriptionBody.hidden));
    };

    const response = await secureFetch(`/api/files/properties?path=${encodeURIComponent(path)}`);

    if (!response.ok) {
        propertiesBody.innerHTML = "Ошибка загрузки";
        return;
    }

    const data = await response.json();

    propertiesBody.innerHTML = renderFullProperties(data);

    if (data.type === "folder") {
        propertiesBody.innerHTML += `<div id="folderStatsBlock">Считаем размер папки...</div>`;

        const statsResponse = await secureFetch(`/api/files/properties/folder-stats?path=${encodeURIComponent(path)}`);

        if (statsResponse.ok) {
            const stats = await statsResponse.json();
            const merged = {...data, ...stats};

            propertiesBody.innerHTML = renderFullProperties(merged);
        }
    }
}

function renderBasicProperties(data) {
    return `
        <div>
            <div><b>Имя:</b> ${escapeHtml(data.name)}</div>
            <div><b>Тип:</b> ${escapeHtml(data.type)}</div>
            <div><b>Дата:</b> ${escapeHtml(data.created)}</div>
        </div>
    `;
}

function initLazyMetadata(cards = gallery.querySelectorAll(".card[data-path]")) {
    if (!window.metadataObserver) {
        window.metadataObserver = new IntersectionObserver((entries, observer) => {
            const paths = [];
            for (const entry of entries) {
                if (!entry.isIntersecting) continue;
                observer.unobserve(entry.target);
                paths.push(entry.target.dataset.path);
            }
            enqueueMetadata(paths);
        }, {root: null, rootMargin: "300px"});
    }
    cards.forEach(card => window.metadataObserver.observe(card));
}

function renderFullProperties(data) {
    return `
        <div>
            ${propRow("Имя", data.name)}
            ${propRow("Тип", data.type)}
            ${propRow("Размер", formatFileSize(data.size))}
            ${propRow("Файлов внутри", data.fileCount)}
            ${propRow("Папок внутри", data.folderCount)}
            ${propRow("Длительность", data.duration)}
            ${propRow("Разрешение", data.resolution)}
            ${propRow("Устройство", data.device)}
            ${propRow("Координаты", data.location)}
            ${propRow("Дата создания", data.created)}
            ${propRow("Дата изменения", data.modified)}
        </div>
    `;
}

function propRow(label, value) {
    if (value === null || value === undefined || value === "" || value === "0") {
        return "";
    }

    return `<div><b>${label}:</b> ${escapeHtml(String(value))}</div>`;
}

function initLazyThumbs(images = gallery.querySelectorAll("img.lazy-thumb[data-src]")) {
    if (!window.thumbObserver) {
        const session = thumbSession;
        window.thumbObserver = new IntersectionObserver((entries, observer) => {
            for (const entry of entries) {
                if (!entry.isIntersecting) continue;
                const img = entry.target;
                observer.unobserve(img);
                const src = img.dataset.src;
                if (!src || session !== thumbSession) continue;
                img.onload = img.onerror = null;
                img.onerror = () => {
                    img.onerror = null;
                    img.src = img.classList.contains("video-thumb-img")
                        ? "/video-placeholder.png" : "/image-placeholder.png";
                };
                img.removeAttribute("data-src");
                img.src = src;
            }
        }, {root: null, rootMargin: "500px"});
    }
    images.forEach(img => window.thumbObserver.observe(img));
}

async function removeItem(path, name) {
    if (!confirm(`Удалить "${name}"?`)) return;

    const response = await secureFetch(`/api/files?path=${encodeURIComponent(path)}`, {
        method: "DELETE"
    });

    if (!response.ok) {
        alert("Ошибка удаления");
        return;
    }

    await loadFiles(currentPath);
}

async function uploadPublicShareFiles(files) {
    if (!files || !files.length) return;

    showLoadingGif();

    try {
        const formData = new FormData();

        for (const file of files) {
            formData.append("files", file);
        }

        const response = await secureFetch(
            `/share/${SHARE_TOKEN}/upload?path=${encodeURIComponent(currentPath)}`,
            {
                method: "POST",
                body: formData
            }
        );

        if (!response.ok) {
            const text = await response.text();
            alert("Не удалось загрузить файлы:\n" + text);
            return;
        }

        await loadFiles(currentPath);

    } finally {
        hideLoadingGif();
    }
}

fileInput.addEventListener("change", () => {

    for (const file of fileInput.files) {
        const existing = Array.from(transferTasks.values()).find(task =>
            task.kind === "upload" &&
            task.fileMeta &&
            task.fileMeta.name === file.name &&
            task.fileMeta.size === file.size &&
            task.fileMeta.lastModified === file.lastModified &&
            task.status === "waiting_file"
        );

        if (existing) {
            existing.file = file;
            existing.status = "queued";

            if (!uploadQueue.find(t => t.id === existing.id)) {
                uploadQueue.push(existing);
            }
        } else {
            addUpload(file);
        }
    }

    saveTransferTasks();
    renderTransferList();
    processUploadQueue();

    fileInput.value = "";
});
newFolderBtn.addEventListener("click", openCreateFolderModal);

upBtn.addEventListener("click", async () => {
    const targetPath = parentPath || "";

    if (window.thumbObserver) window.thumbObserver.disconnect();
    if (window.metadataObserver) window.metadataObserver.disconnect();

    hideMetadataLoadingModal();
    hideThumbLoadingModal();
    hideFolderLoadingRing();
    resetGroupingState();
    await loadFiles(targetPath, {showPrepareModal: false});
});

function openCreateFolderModal() {
    createFolderInput.value = "";
    createFolderModal.classList.remove("hidden");

    setTimeout(() => {
        createFolderInput.focus();
    }, 50);
}

function closeCreateFolderModal() {
    createFolderModal.classList.add("hidden");
    createFolderInput.value = "";
}

async function confirmCreateFolder() {
    showLoadingGif();

    try {
        const name = createFolderInput.value.trim();
        if (!name) {
            alert("Введите имя папки");
            return;
        }

        const formData = new URLSearchParams();
        formData.append("name", name);

        const response = await secureFetch(`/api/files/folder?path=${encodeURIComponent(currentPath)}`, {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded"
            },
            body: formData
        });

        if (!response.ok) {
            const text = await response.text();
            alert("Ошибка создания папки:\n" + text);
            return;
        }

        closeCreateFolderModal();
        await loadFiles(currentPath);
    } finally {
        hideLoadingGif();
    }
}

function openViewerByPath(relativePath) {
    viewerIndex = viewerItems.findIndex(item => item.relativePath === relativePath);
    if (viewerIndex === -1) return;
    renderViewerItem();
    viewer.classList.remove("hidden");
}

function resetViewerZoom() {
    viewerZoom = 1;
    viewerPanX = 0;
    viewerPanY = 0;
    viewerDragging = false;
    viewerLastTouchDistance = null;
}

function applyViewerZoom() {
    const img = document.getElementById("viewerImage");
    if (!img) return;

    img.style.transform =
        `translate(${viewerPanX}px, ${viewerPanY}px) scale(${viewerZoom})`;

    img.classList.toggle("zoomed", viewerZoom > 1);
}

function getTouchDistance(touches) {
    const dx = touches[0].clientX - touches[1].clientX;
    const dy = touches[0].clientY - touches[1].clientY;

    return Math.sqrt(dx * dx + dy * dy);
}

function initViewerImageZoom() {
    cleanupViewerInput();
    viewerInputController = new AbortController();
    const signal = viewerInputController.signal;
    const img = document.getElementById("viewerImage");
    if (!img) return;

    img.addEventListener("wheel", (e) => {
        e.preventDefault();

        const delta = e.deltaY < 0 ? 0.15 : -0.15;

        viewerZoom = Math.min(5, Math.max(1, viewerZoom + delta));

        if (viewerZoom === 1) {
            viewerPanX = 0;
            viewerPanY = 0;
        }

        applyViewerZoom();
    }, {passive: false, signal});

    img.addEventListener("dblclick", () => {
        if (viewerZoom === 1) {
            viewerZoom = 2.5;
        } else {
            resetViewerZoom();
        }

        applyViewerZoom();
    }, {signal});

    img.addEventListener("mousedown", (e) => {
        if (viewerZoom <= 1) return;

        viewerDragging = true;
        viewerDragStartX = e.clientX - viewerPanX;
        viewerDragStartY = e.clientY - viewerPanY;
    }, {signal});

    window.addEventListener("mousemove", (e) => {
        if (!viewerDragging) return;

        viewerPanX = e.clientX - viewerDragStartX;
        viewerPanY = e.clientY - viewerDragStartY;

        applyViewerZoom();
    }, {signal});

    window.addEventListener("mouseup", () => {
        viewerDragging = false;
    }, {signal});

    img.addEventListener("touchstart", (e) => {
        if (e.touches.length === 2) {
            viewerLastTouchDistance = getTouchDistance(e.touches);
        }

        if (e.touches.length === 1 && viewerZoom > 1) {
            viewerDragging = true;
            viewerDragStartX = e.touches[0].clientX - viewerPanX;
            viewerDragStartY = e.touches[0].clientY - viewerPanY;
        }
    }, {passive: false, signal});

    img.addEventListener("touchmove", (e) => {
        if (e.touches.length === 2) {
            e.preventDefault();

            const distance = getTouchDistance(e.touches);

            if (viewerLastTouchDistance) {
                const diff = distance - viewerLastTouchDistance;
                viewerZoom = Math.min(5, Math.max(1, viewerZoom + diff * 0.01));
                applyViewerZoom();
            }

            viewerLastTouchDistance = distance;
            return;
        }

        if (e.touches.length === 1 && viewerDragging && viewerZoom > 1) {
            e.preventDefault();

            viewerPanX = e.touches[0].clientX - viewerDragStartX;
            viewerPanY = e.touches[0].clientY - viewerDragStartY;

            applyViewerZoom();
        }
    }, {passive: false, signal});

    img.addEventListener("touchend", () => {
        viewerDragging = false;
        viewerLastTouchDistance = null;

        if (viewerZoom <= 1) {
            resetViewerZoom();
            applyViewerZoom();
        }
    }, {signal});
}

function renderViewerItem() {
    cleanupViewerInput();
    destroyCurrentVideo();
    if (viewerIndex < 0 || viewerIndex >= viewerItems.length) return;

    const item = viewerItems[viewerIndex];
    updateViewerSelectButton();
    if (currentPlayer) {
        currentPlayer?.destroy();
        currentPlayer = null;
    }
    if (item.type === "image") {
        const src =
            item.previewUrl ||
            item.thumbnailUrl ||
            buildImagePreviewUrl(item.relativePath);

        resetViewerZoom();

        viewerBody.innerHTML = `
        <img id="viewerImage"
             class="viewer-image"
             src="${src}"
             alt="${escapeHtml(item.name)}">
    `;

        initViewerImageZoom();
    }

     else if (item.type === "video") {
        const lower = item.name.toLowerCase();
        if (lower.endsWith(".insv") || lower.endsWith(".lrv")) {

            item.hlsSupported = true;

            item.hlsPrepareUrl =
                item.hlsPrepareUrl ||
                `/api/video/hls/prepare?path=${encodeURIComponent(item.relativePath)}`;

            item.hlsStatusUrl =
                item.hlsStatusUrl ||
                `/api/video/hls/status?path=${encodeURIComponent(item.relativePath)}`;

            console.log("VIDEO ITEM:", item.name, {
                hlsSupported: item.hlsSupported,
                hlsPrepareUrl: item.hlsPrepareUrl,
                hlsStatusUrl: item.hlsStatusUrl
            });

            openHlsViewer(item);
            return;
        }

        viewerBody.innerHTML = `
        <video id="player"
               controls
               preload="metadata"
               poster="${buildVideoThumbnailUrl(item.relativePath)}"
               style="width:100%; max-height:75vh;">
            <source src="${buildVideoStreamUrl(item.relativePath)}" type="video/mp4">
        </video>
    `;

        const video = document.getElementById("player");
        currentPlayer = new Plyr(video);
    }
    downloadViewerBtn.onclick = () => {
        const item = viewerItems[viewerIndex];
        const lower = item.name.toLowerCase();

        // если это insv/lrv и открыт preview
        if (lower.endsWith(".insv") || lower.endsWith(".lrv")) {

            openDownloadFormatModal(item, currentPreviewId);
            return;
        }
        // обычные файлы
        window.location.href = item.downloadUrl;
    };

}

sortBtn.onclick = () => {
    updateSortButtonsState();
    sortModal.classList.remove("hidden");
};

closeSortModalBtn.onclick = () => {
    sortModal.classList.add("hidden");
};

sortModal.addEventListener("click", (e) => {
    if (e.target.classList.contains("delete-modal-backdrop")) {
        sortModal.classList.add("hidden");
    }
});

document.querySelectorAll(".sort-field-btn").forEach(btn => {
    btn.addEventListener("click", async () => {
        sortField = btn.dataset.field;
        localStorage.setItem("sortField", sortField);
        updateSortButtonsState();
        await loadFiles(currentPath);
    });
});

closePropertiesModalBtn.onclick = closePropertiesModal;
downloadOriginalFormatBtn.onclick = () => {
    selectedDownloadFormat = "original";
    updateDownloadFormatButtons();
};

downloadMp4FormatBtn.onclick = () => {
    selectedDownloadFormat = "mp4";
    updateDownloadFormatButtons();
};

cancelDownloadFormatBtn.onclick = closeDownloadFormatModal;

downloadFormatModal.addEventListener("click", (e) => {
    if (e.target.classList.contains("delete-modal-backdrop")) {
        closeDownloadFormatModal();
    }
});
confirmDownloadFormatBtn.onclick = async () => {
    if (!pendingDownloadItem) return;

    const item = pendingDownloadItem;
    const format = selectedDownloadFormat;
    const previewId = pendingDownloadPreviewId;

    closeDownloadFormatModal();

    if (format === "original") {
        if (previewId) {
            showDownloadActionToast(item);
            return;
        }
        window.location.href = item.downloadUrl;
        return;
    }

    if (format === "mp4") {
        if (previewId) {
            window.location.href =
                `/api/files/download/mp4-file?previewId=${encodeURIComponent(previewId)}`;
            return;
        }

        const form = new URLSearchParams();
        form.append("path", item.relativePath);

        const response = await secureFetch("/api/files/download/mp4-start", {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded"
            },
            body: form
        });

        if (!response.ok) {
            alert("Не удалось подготовить MP4");
            return;
        }

        const data = await response.json();

        window.location.href =
            `/api/files/download/mp4-file?previewId=${encodeURIComponent(data.previewId)}`;

        setTimeout(() => {
            secureFetch(`/api/files/preview/cancel?previewId=${encodeURIComponent(data.previewId)}`, {
                method: "DELETE"
            });
        }, 5000);
    }
};
propertiesModal.addEventListener("click", (e) => {
    if (e.target.classList.contains("delete-modal-backdrop")) {
        propertiesModal.classList.add("hidden");
    }
});
sortDirectionBtn.onclick = () => {
    sortDirection = sortDirection === "asc" ? "desc" : "asc";

    localStorage.setItem("sortField", sortField);
    localStorage.setItem("sortDirection", sortDirection);

    updateSortButtonsState();
    loadFiles(currentPath);
};
cancelPreviewBuildBtn.onclick = async () => {
    previewWaitController?.abort();

    if (currentPreviewId) {
        await secureFetch(`/api/files/preview/cancel?previewId=${encodeURIComponent(currentPreviewId)}`, {
            method: "DELETE"
        });
    }

    currentPreviewId = null;
    previewBuildModal.classList.add("hidden");
};

async function showPrevItem() {

    if (!viewerItems.length) return;

    const currentViewerItem =
        viewerIndex >= 0 && viewerItems[viewerIndex]
            ? viewerItems[viewerIndex]
            : null;

    if (currentViewerItem) {
        cancelHlsConversion(currentViewerItem).catch(console.error);
    }

    destroyCurrentVideo();

    viewerBody.innerHTML = ""; // 👈 важно

    await cleanupCurrentPreview();

    viewerIndex = (viewerIndex - 1 + viewerItems.length) % viewerItems.length;
    renderViewerItem();
}

async function showNextItem() {

    if (!viewerItems.length) return;

    const currentViewerItem =
        viewerIndex >= 0 && viewerItems[viewerIndex]
            ? viewerItems[viewerIndex]
            : null;

    if (currentViewerItem) {
        cancelHlsConversion(currentViewerItem).catch(console.error);
    }

    destroyCurrentVideo();

    viewerBody.innerHTML = ""; // 👈 важно

    await cleanupCurrentPreview();

    viewerIndex = (viewerIndex + 1) % viewerItems.length;
    renderViewerItem();
}

function toggleFullscreen() {
    if (!document.fullscreenElement) {
        viewer.requestFullscreen?.();
    } else {
        document.exitFullscreen?.();
    }
}

async function closeViewerModal() {
    cleanupViewerInput();
    previewWaitController?.abort();
    const currentViewerItem =
        viewerIndex >= 0 && viewerItems[viewerIndex]
            ? viewerItems[viewerIndex]
            : null;

    destroyCurrentVideo();

    viewerBody.innerHTML = "";

    if (currentViewerItem) {
        await cancelHlsConversion(currentViewerItem).catch(console.error);
    }

    await cleanupCurrentPreview();

    viewer.classList.add("hidden");
    viewerIndex = -1;
}

createShareBtn?.addEventListener("click", createShareLink);
cancelShareBtn?.addEventListener("click", closeShareModal);
closeShareModalBtn?.addEventListener("click", closeShareModal);
copyShareUrlBtn?.addEventListener("click", copyCurrentShareLink);

disableShareUrlBtn?.addEventListener("click", disableCurrentShareLink);

shareModal?.addEventListener("click", (e) => {
    if (e.target.classList.contains("delete-modal-backdrop")) {
        closeShareModal();
    }
});

toggleTopbarBtn?.addEventListener("click", toggleTopbar);

confirmCreateFolderBtn.addEventListener("click", confirmCreateFolder);
cancelCreateFolderBtn.addEventListener("click", closeCreateFolderModal);

createFolderModal.addEventListener("click", (e) => {
    if (e.target.classList.contains("delete-modal-backdrop")) {
        closeCreateFolderModal();
    }
});

createFolderInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
        confirmCreateFolder();
    }
});

confirmDeleteBtn.addEventListener("click", confirmDelete);
cancelDeleteBtn.addEventListener("click", closeDeleteModal);
bulkDeleteBtn.onclick = openBulkDeleteModal;
selectAllBtn.onclick = () => {
    for (const item of currentItems) {
        selectedItems.set(item.relativePath, {
            path: item.relativePath,
            name: item.name,
            directory: item.directory
        });
    }

    for (const item of currentItems) syncSelectionToGallery(item.relativePath, true);
    updateBulkButtons();
};
deleteModal.addEventListener("click", (e) => {
    if (e.target.classList.contains("delete-modal-backdrop")) {
        closeDeleteModal();
    }
});
clearSelectionBtn.onclick = () => {
    selectedItems.clear();
    for (const item of currentItems) syncSelectionToGallery(item.relativePath, false);
    updateBulkButtons();
};

bulkDownloadBtn.onclick = openBulkDownloadModal;
confirmBulkDownloadBtn.onclick = executeBulkDownload;

cancelBulkDownloadBtn.onclick = () => {
    bulkDownloadPollingCancelled = true;
    activeBulkDownloadId = null;

    bulkDownloadModal.classList.add("hidden");

    confirmBulkDownloadBtn.disabled = false;
    cancelBulkDownloadBtn.disabled = false;
};

bulkDownloadModal.addEventListener("click", (e) => {
    if (e.target.classList.contains("delete-modal-backdrop")) {
        bulkDownloadPollingCancelled = true;
        activeBulkDownloadId = null;

        bulkDownloadModal.classList.add("hidden");

        confirmBulkDownloadBtn.disabled = false;
        cancelBulkDownloadBtn.disabled = false;
    }
});
bulkMoveBtn.onclick = () => {
    if (!selectedItems.size) return;

    bulkMoveMode = true;
    moveSourcePath = null;
    moveSourceName = null;
    selectedMovePath = "";

    moveModalTargetName.textContent = `Перемещаем объектов: ${selectedItems.size}`;
    selectedMovePathEl.textContent = `Выбрано: /`;
    folderTreeContainer.innerHTML = `<div>Загрузка папок...</div>`;

    moveModal.classList.remove("hidden");

    secureFetch("/api/files/folders/tree")
        .then(r => r.json())
        .then(tree => {
            folderTreeContainer.innerHTML = "";
            folderTreeContainer.appendChild(renderFolderTree(tree));
        });
};
folderListBtn.addEventListener("click", openFolderListModal);
closeFolderListModalBtn.addEventListener("click", closeFolderListModal);
cancelFolderListBtn.addEventListener("click", closeFolderListModal);
goToSelectedFolderBtn.addEventListener("click", goToSelectedFolder);

resumeAllTransfersBtn.classList.add("resume-all-active");

folderListModal.addEventListener("click", (e) => {
    if (e.target.classList.contains("move-modal-backdrop")) {
        closeFolderListModal();
    }
});

confirmBulkMoveBtn.onclick = () => {
    if (bulkMoveMode) {
        executeBulkMove();
    } else {
        executeSingleMove();
    }
};
cancelBulkMoveBtn.onclick = (e) => {
    e.preventDefault();
    e.stopPropagation();

    bulkMoveConfirmModal.classList.add("hidden");
};
bulkMoveConfirmModal.addEventListener("click", (e) => {
    if (Date.now() < ignoreMoveConfirmBackdropUntil) {
        e.preventDefault();
        e.stopPropagation();
        return;
    }

    if (e.target.classList.contains("delete-modal-backdrop")) {
        bulkMoveConfirmModal.classList.add("hidden");
    }
});
expandAllFoldersBtn.addEventListener("click", () => {
    expandAllTreeNodes(folderListTreeContainer);
});

collapseAllFoldersBtn.addEventListener("click", () => {
    collapseAllTreeNodes(folderListTreeContainer);
});

expandMoveTreeBtn.addEventListener("click", () => {
    expandAllTreeNodes(folderTreeContainer);
});

collapseMoveTreeBtn.addEventListener("click", () => {
    collapseAllTreeNodes(folderTreeContainer);
});

closeMoveModalBtn.addEventListener("click", closeMoveModal);
cancelMoveBtn.addEventListener("click", closeMoveModal);

function handleConfirmMovePress(e) {
    e.preventDefault();
    e.stopPropagation();

    confirmMove();
}

confirmMoveBtn.onclick = null;
confirmMoveBtn.addEventListener("pointerup", (e) => {
    e.preventDefault();
    e.stopPropagation();
    e.stopImmediatePropagation();

    confirmMove();
}, {passive: false});

moveModal.addEventListener("click", (e) => {
    if (e.target.classList.contains("move-modal-backdrop")) {
        closeMoveModal();
    }
});
closeViewer.addEventListener("click", closeViewerModal);
prevViewerBtn.addEventListener("click", showPrevItem);
nextViewerBtn.addEventListener("click", showNextItem);
selectViewerBtn.addEventListener("click", () => {
    if (viewerIndex < 0 || viewerIndex >= viewerItems.length) return;

    const item = viewerItems[viewerIndex];

    let selected;

    if (selectedItems.has(item.relativePath)) {
        selectedItems.delete(item.relativePath);
        selected = false;
    } else {
        selectedItems.set(item.relativePath, {
            path: item.relativePath,
            name: item.name,
            directory: item.directory
        });
        selected = true;
    }

    syncSelectionToGallery(item.relativePath, selected);
    updateViewerSelectButton();
    updateBulkButtons();
});
fullscreenViewerBtn.addEventListener("click", toggleFullscreen);
homeBtn.addEventListener("click", () => {
    resetGroupingState();
    loadFiles(""); // переход в корень
});

viewer.addEventListener("click", (e) => {
    if (e.target.classList.contains("viewer-backdrop")) {
        closeViewerModal();
    }
});
document.addEventListener("keydown", e => {
    if (e.key === "Escape") {
        closeRenameModal();
    }
});
document.addEventListener("keydown", (e) => {
    if (viewer.classList.contains("hidden")) return;

    if (e.key === "Escape") closeViewerModal();
    if (e.key === "ArrowLeft") showPrevItem();
    if (e.key === "ArrowRight") showNextItem();
});
document.addEventListener("keydown", e => {

    if (e.key === "Escape") {

        closePropertiesModal();
        closeRenameModal();
    }
});

function updateNavButtons() {
    if (!currentPath) {
        upBtn.style.display = "none";
        homeBtn.style.display = "none";
    } else {
        upBtn.style.display = "inline-flex";
        homeBtn.style.display = "inline-flex";
    }
}

async function openMoveModal(sourcePath, sourceName) {
    bulkMoveMode = false;

    moveSourcePath = sourcePath;
    moveSourceName = sourceName;
    selectedMovePath = "";

    moveModalTargetName.textContent = `Перемещаем: ${sourceName}`;
    selectedMovePathEl.textContent = `Выбрано: /`;
    folderTreeContainer.innerHTML = `<div>Загрузка папок...</div>`;

    moveModal.classList.remove("hidden");

    const response = await secureFetch("/api/files/folders/tree");

    if (!response.ok) {
        folderTreeContainer.innerHTML = `<div>Не удалось загрузить список папок</div>`;
        return;
    }

    const tree = await response.json();

    folderTreeContainer.innerHTML = "";
    folderTreeContainer.appendChild(renderFolderTree(tree));
}

async function sendChunk(formData, signal) {
    for (let attempt = 0; attempt < 3; attempt++) {
        try {
            const res = await secureFetch("/api/files/upload-chunk", {
                method: "POST",
                body: formData, signal
            });

            if (!res.ok) throw new Error("upload error");
            return;
        } catch (e) {
            if (signal?.aborted || attempt === 2) throw e;
            await new Promise(r => setTimeout(r, 1000));
        }
    }
}

function selectMoveFolder(node, row) {
    selectedMovePath = node.relativePath || "";

    selectedMovePathEl.textContent =
        `Выбрано: ${selectedMovePath ? "/" + selectedMovePath : "/"}`;

    folderTreeContainer
        .querySelectorAll(".folder-tree-row.selected")
        .forEach(el => el.classList.remove("selected"));

    row.classList.add("selected");
}

function renderFolderTree(node) {
    const wrapper = document.createElement("div");
    wrapper.className = "folder-tree-node";

    const row = document.createElement("div");
    row.className = "folder-tree-row";

    const toggle = document.createElement("div");
    toggle.className = "folder-toggle";

    const label = document.createElement("div");
    label.className = "folder-label";
    label.textContent = node.name === "/" ? "📁 Корень /" : `📁 ${node.name}`;

    const hasChildren = node.children && node.children.length > 0;
    toggle.textContent = hasChildren ? "▶" : "";

    row.appendChild(toggle);
    row.appendChild(label);
    wrapper.appendChild(row);

    const childrenContainer = document.createElement("div");
    childrenContainer.className = "folder-children hidden";

    if (hasChildren) {
        for (const child of node.children) {
            childrenContainer.appendChild(renderFolderTree(child));
        }

        wrapper.appendChild(childrenContainer);
    }

    label.addEventListener("click", (e) => {
        e.preventDefault();
        e.stopPropagation();
        selectMoveFolder(node, row);
    });

    row.addEventListener("click", (e) => {
        e.preventDefault();
        e.stopPropagation();
        selectMoveFolder(node, row);
    });

    toggle.addEventListener("click", (e) => {
        e.preventDefault();
        e.stopPropagation();

        if (!hasChildren) return;

        childrenContainer.classList.toggle("hidden");
        toggle.textContent = childrenContainer.classList.contains("hidden") ? "▶" : "▼";
    });

    return wrapper;
}

function closeMoveModal() {
    moveModal.classList.add("hidden");

    bulkMoveMode = false;

    moveSourcePath = null;
    moveSourceName = null;
    selectedMovePath = "";

    folderTreeContainer.innerHTML = "";
    selectedMovePathEl.textContent = "Выбрано: /";
    moveModalTargetName.textContent = "";
}

function formatBytes(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}

function formatFileSize(bytes) {
    if (!bytes && bytes !== 0) return "";

    const sizes = ["B", "KB", "MB", "GB", "TB"];
    let i = 0;

    while (bytes >= 1024 && i < sizes.length - 1) {
        bytes /= 1024;
        i++;
    }

    return bytes.toFixed(i === 0 ? 0 : 1) + " " + sizes[i];
}

const topProgressLabelSafe = toggleTransfersBtn?.querySelector(".tp-label");
if (topProgressLabelSafe) {
    topProgressLabelSafe.textContent = "⬆ Загрузки";
}
let transfersCollapsed = false;

collapseTransfersBtn.onclick = () => {
    toggleTransfersBtn.click();
};

toggleTransfersBtn.onclick = () => {
    transfersCollapsed = !transfersCollapsed;

    if (transfersCollapsed) {
        transferPanel.classList.add("collapsed");
        if (topProgressLabelSafe) {
            topProgressLabelSafe.textContent = "⬆ Загрузки";
        }
    } else {
        transferPanel.classList.remove("collapsed");
        if (topProgressLabelSafe) {
            topProgressLabelSafe.textContent = "⬇ Загрузки";
        }
    }
};

function debounce(fn, delay = 100) {
    let timer;

    return function (...args) {
        clearTimeout(timer);
        timer = setTimeout(() => fn.apply(this, args), delay);
    };
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

const savedTopbarState = localStorage.getItem("topbarCollapsed");
if (savedTopbarState === "1") {
    setTopbarCollapsed(true);
}
restoreTransferTasks();
hideInPublicMode();
document.addEventListener("DOMContentLoaded", () => {
    syncPhotoSearchLayout();
    if (photoSearchForm && !PUBLIC_SHARE_MODE && topbar && typeof ResizeObserver !== "undefined") {
        const observer = new ResizeObserver(syncPhotoSearchLayout);
        observer.observe(topbar);
    }
    loadFiles();
    window.metadataLoadingModal = document.getElementById("metadataLoadingModal");
    window.metadataLoadingBar = document.getElementById("metadataLoadingBar");
    window.metadataLoadingCount = document.getElementById("metadataLoadingCount");
    window.metadataLoadingText = document.getElementById("metadataLoadingText");
});

async function handleLoadMoreScroll() {
    if (!currentPreparedJobId || preparedAllLoaded || loading) return;

    const scrollTop = window.scrollY || document.documentElement.scrollTop;
    const windowHeight = window.innerHeight;
    const fullHeight = document.documentElement.scrollHeight;

    const nearBottom = scrollTop + windowHeight >= fullHeight - 1200;

    if (nearBottom) {
        await loadPreparedPage(currentPreparedJobId);
    }
}

window.addEventListener("scroll", debounce(handleLoadMoreScroll, 100), {passive: true});

updateSortButtonsState();
updateBulkButtons();
