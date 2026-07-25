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
    //console.log("CSRF TOKEN:", token);
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

/*function onClick(element, handler) {
    if (element) {
        element.addEventListener("click", handler);
    }
}*/

let parentPath = "";
let currentPlayer = null;

let deleteTargetPath = null;
let deleteTargetName = null;

let renameTargetPath = null;
let renameTargetName = null;

let currentPropertiesPath = null;
let currentPropertiesName = null;

let currentItems = [];
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
let previewStatusTimer = null;

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
let metadataRunning = 0;
const MAX_METADATA_REQUESTS = window.innerWidth <= 768 ? 2 : 4; // 🔥 для телефона критично
const METADATA_BATCH_SIZE = window.innerWidth <= 768 ? 20 : 80;
let currentPreparedTotal = 0;
const PAGE_LIMIT = 1000;

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

/*function hideInPublicMode() {

    if (!PUBLIC_SHARE_MODE) {
        return;
    }

    [
        newFolderBtn,
        folderListBtn,
        totalCacheBtn,
        selectAllBtn,
        clearSelectionBtn,
        bulkMoveBtn,
        bulkDeleteBtn,
        bulkDownloadBtn
    ].forEach(e => e?.remove());

}*/

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
            /*currentPlayer = new Plyr(video);*/
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
    /*startHlsProgressPolling(item);*/
    video.play().catch(console.warn);
}

/*function playHlsInViewer(playlistUrl, item) {
    destroyCurrentVideo();
    viewerBody.innerHTML = `
        <video id="player"
               controls
               preload="auto"
               poster="${buildVideoThumbnailUrl(item.relativePath)}"
               style="width:100%; max-height:75vh;">
        </video>
    `;

    const video = document.getElementById("player");

    if (video.canPlayType("application/vnd.apple.mpegurl")) {
        video.src = playlistUrl;
        currentPlayer = new Plyr(video);
        video.play();
        return;
    }

    if (window.Hls && Hls.isSupported()) {
        const hls = new Hls({
            enableWorker: true,
            lowLatencyMode: false
        });

        hls.loadSource(playlistUrl);
        hls.attachMedia(video);

        hls.on(Hls.Events.MANIFEST_PARSED, () => {
            currentPlayer = new Plyr(video);
            video.play();
        });

        return;
    }

    viewerBody.innerHTML = `<div>Этот браузер не поддерживает HLS</div>`;
}*/
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
            currentPlayer.destroy();
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

async function updateHlsProgress(item) {
    if (!item || !item.relativePath) return;

    const ring = document.getElementById("hlsProgressRing");
    const fill = document.getElementById("hlsProgressFill");
    const text = document.getElementById("hlsProgressText");

    if (!ring || !fill || !text) return;

    try {
        const response = await secureFetch(
            `/api/video/hls/progress?path=${encodeURIComponent(item.relativePath)}`
        );

        if (!response.ok) return;

        const state = await response.json();

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

    updateHlsProgress(item);

    hlsProgressTimer = setInterval(() => {
        updateHlsProgress(item);
    }, 1000);
}

function stopHlsProgressPolling() {
    if (hlsProgressTimer) {
        clearInterval(hlsProgressTimer);
        hlsProgressTimer = null;
    }

    currentHlsProgressItem = null;
}

async function waitHlsReady(item, playlistUrl) {
    while (true) {
        await new Promise(resolve => setTimeout(resolve, 1500));

        const response = await secureFetch(item.hlsStatusUrl);

        if (!response.ok) {
            viewerBody.innerHTML = `<div>Ошибка проверки статуса HLS</div>`;
            return;
        }

        const status = await response.text();

        if (status.includes("READY") || status.includes("PLAYABLE")) {
            playHlsInViewer(playlistUrl, item);
            return;
        }

        if (status.includes("FAILED")) {
            viewerBody.innerHTML = `<div>Не удалось подготовить HLS-видео</div>`;
            return;
        }

        /*viewerBody.innerHTML = `<div>Подготавливаем HLS-видео...</div>`;*/
    }
}

let currentHls = null;

/*function destroyCurrentVideo() {
    const video = document.getElementById("player");

    if (video) {
        try {
            video.pause();
            video.removeAttribute("src");
            video.load();
        } catch (e) {
            console.warn("Video cleanup warning", e);
        }
    }

    if (currentPlayer) {
        currentPlayer.destroy();
        currentPlayer = null;
    }

    if (currentHls) {
        currentHls.destroy();
        currentHls = null;
    }
}*/
function destroyCurrentVideo() {
    stopHlsProgressPolling();
    destroyVideoOnly();
    /*if (currentHls) {
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
            currentPlayer.destroy();
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
    }*/
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
    /*viewerBody.innerHTML = `<div>Подготавливаем HLS-видео...</div>`;*/
    renderHlsProgressRing();
    startHlsProgressPolling(item);

    const response = await secureFetch(item.hlsPrepareUrl, {
        method: "POST"
    });

    if (!response.ok) {
        viewerBody.innerHTML = `<div>Не удалось запустить подготовку HLS</div>`;
        return;
    }

    const state = await response.json();

    if (state.status === "READY" || state.status === "PLAYABLE") {
        playHlsInViewer(state.playlistUrl, item);
        return;
    }

    if (state.status === "RUNNING") {
        await waitHlsReady(item, state.playlistUrl);
        return;
    }

    viewerBody.innerHTML = `<div>Ошибка подготовки HLS</div>`;
}

/*async function openInsvAsHls(item) {
    showVideoPreparingModal("Готовлю видео для просмотра...");

    const prepareResponse = await secureFetch(item.hlsPrepareUrl, {
        method: "POST"
    });

    const state = await prepareResponse.json();

    if (state.status === "READY") {
        playHls(state.playlistUrl);
        hideVideoPreparingModal();
        return;
    }

    if (state.status === "RUNNING") {
        await waitUntilHlsReady(item.hlsStatusUrl, state.playlistUrl);
        hideVideoPreparingModal();
        return;
    }

    hideVideoPreparingModal();
    alert("Не удалось подготовить видео");
}*/

/*async function waitUntilHlsReady(statusUrl, playlistUrl) {
    while (true) {
        await sleep(2000);

        const response = await secureFetch(statusUrl);
        const status = await response.text();

        if (status.includes("READY")) {
            playHls(playlistUrl);
            return;
        }

        if (status.includes("FAILED")) {
            throw new Error("HLS conversion failed");
        }
    }
}*/

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

/*function playHls(playlistUrl) {
    const video = document.getElementById("viewerVideo");

    if (video.canPlayType("application/vnd.apple.mpegurl")) {
        video.src = playlistUrl;
        video.play();
        return;
    }

    if (Hls.isSupported()) {
        const hls = new Hls({
            enableWorker: true,
            lowLatencyMode: false
        });

        hls.loadSource(playlistUrl);
        hls.attachMedia(video);

        hls.on(Hls.Events.MANIFEST_PARSED, () => {
            video.play();
        });

        return;
    }

    alert("Ваш браузер не поддерживает HLS");
}*/
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

/*function updatePublicShareButtons(permission) {
    if (!PUBLIC_SHARE_MODE) return;

    const canUpload = permission === "UPLOAD" || permission === "MANAGE";
    const canDelete = permission === "MANAGE";
    const canDownload = permission === "DOWNLOAD" || permission === "MANAGE";

    if (newFolderBtn) newFolderBtn.style.display = "none";
    if (folderListBtn) folderListBtn.style.display = "none";
    if (totalCacheBtn) totalCacheBtn.style.display = "none";

    if (selectAllBtn) selectAllBtn.style.display = "none";
    if (clearSelectionBtn) clearSelectionBtn.style.display = "none";
    if (bulkMoveBtn) bulkMoveBtn.style.display = "none";
    if (bulkDownloadBtn) bulkDownloadBtn.style.display = "none";
    if (bulkDeleteBtn) bulkDeleteBtn.style.display = "none";

    if (fileInput) {
        const uploadLabel = fileInput.closest(".upload-label");
        if (uploadLabel) {
            uploadLabel.style.display = canUpload ? "inline-flex" : "none";
        }
    }

    document.querySelectorAll(".card-actions .danger").forEach(btn => {
        btn.style.display = canDelete ? "inline-flex" : "none";
    });

    document.querySelectorAll(".card-actions a").forEach(link => {
        link.style.display = canDownload ? "inline-flex" : "none";
    });

    if (downloadViewerBtn) {
        downloadViewerBtn.style.display = canDownload ? "inline-flex" : "none";
    }
}*/

/*async function loadFilesPrepared(path = "") {*/
async function loadFilesPrepared(path = "", options = {}) {
    /*if (PUBLIC_SHARE_MODE) {
        return loadPublicShareFiles(path);
    }*/
    showLoadingGif();
    try {
        const showPrepareModal = options.showPrepareModal !== false;
        thumbSession++; // 🔥 убиваем старые загрузки

// остановка метаданных
        METADATA_QUEUE.length = 0;
        metadataRunning = 0;

// сброс модалок
        hideThumbLoadingModal();
        const sessionId = ++folderLoadSession;
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

        await loadSharedLinksIndex();

        currentPathEl.textContent = currentPath ? "/" + currentPath : "/";
        currentPathEl.classList.remove("path-expanded");

        if (PUBLIC_SHARE_MODE) {
            const infoRes = await secureFetch(`/share/${SHARE_TOKEN}/info`);

            if (infoRes.ok) {
                const info = await infoRes.json();
                window.PUBLIC_SHARE_PERMISSION = info.permission;
            }
        }

        showFolderLoadingRing(0);

        if (showPrepareModal) {
            showMetadataLoadingModal();
        }

        const res = await secureFetch(
            /*`/api/files/prepare-folder?path=${encodeURIComponent(pathForLoading)}&sortField=${encodeURIComponent(sortField)}&sortDirection=${encodeURIComponent(sortDirection)}`,*/
            `/api/files/prepare-folder?path=${encodeURIComponent(pathForLoading)}&sortField=${encodeURIComponent(sortField)}&sortDirection=${encodeURIComponent(sortDirection)}&groupMode=${encodeURIComponent(groupingMode)}&periodEnabled=${periodFilterEnabled}&periodFrom=${encodeURIComponent(periodFromValue)}&periodTo=${encodeURIComponent(periodToValue)}`,
            {method: "POST"}
        );

        const {jobId} = await res.json();

        if (!res.ok) {
            gallery.innerHTML = `<div class="empty-folder-message">Ссылка недоступна или срок действия истёк</div>`;
            return;
        }
        if (sessionId !== folderLoadSession) {
            hideLoadingGif();
            return;
        }
        let ready = false;

        while (!ready) {
            const statusRes = await secureFetch(`/api/files/prepare-status?jobId=${encodeURIComponent(jobId)}`);
            const status = await statusRes.json();
            if (sessionId !== folderLoadSession) {
                hideMetadataLoadingModal();
                hideLoadingGif();
                return;
            }
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
        if (sessionId !== folderLoadSession) {
            hideMetadataLoadingModal();

            return;
        }
        hideMetadataLoadingModal();

        currentPreparedJobId = jobId;

        await loadPreparedPage(jobId);

        if (PUBLIC_SHARE_MODE) {
            updatePublicShareButtons(window.PUBLIC_SHARE_PERMISSION);
        }

        updateNavButtons();
    } finally {
        hideLoadingGif();
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

    loading = true;

    try {
        const res = await secureFetch(
            `/api/files/prepared-items?jobId=${encodeURIComponent(jobId)}&offset=${offset}&limit=${PAGE_LIMIT}`
        );

        if (!res.ok) {
            preparedAllLoaded = true;
            hideFolderLoadingRing();
            hideLoadingGif();
            return;
        }

        const data = await res.json();
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

    } finally {
        loading = false;
    }
}

async function openTotalCacheModal() {

    totalCacheModal.classList.remove("hidden");

    if (totalCacheTimer) {
        clearInterval(totalCacheTimer);
    }

    // просто читаем текущий status
    const response = await secureFetch("/api/files/total-cache/raw-status");

    if (!response.ok) return;

    const status = await response.json();

    renderTotalCacheStatus(status);

    // polling только если процесс уже идёт
    if (status.running || status.stage?.includes("Анализ")) {
        totalCacheTimer = setInterval(updateTotalCacheStatus, 1000);
    }
}

async function showTotalCacheStatus() {

    await secureFetch("/api/files/total-cache/status-start", {
        method: "POST"
    });

    await secureFetch("/api/files/total-cache/status");

    if (totalCacheTimer) {
        clearInterval(totalCacheTimer);
    }

    totalCacheTimer = setInterval(updateTotalCacheStatus, 1000);
}

async function pauseTotalCache() {
    await secureFetch("/api/files/total-cache/pause", {
        method: "POST"
    });

    await updateTotalCacheStatus();
}

async function cancelTotalCache() {
    if (totalCacheTimer) {
        clearInterval(totalCacheTimer);
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
        clearInterval(totalCacheTimer);
    }

    updateTotalCacheStatus();

    totalCacheTimer = setInterval(updateTotalCacheStatus, 1000);
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

async function updateTotalCacheStatus() {

    const response = await secureFetch("/api/files/total-cache/raw-status");

    if (!response.ok) return;

    const status = await response.json();

    renderTotalCacheStatus(status);

    // остановить polling если всё завершилось
    if (
        !status.running
        && !status.stage?.includes("Анализ")
    ) {
        clearInterval(totalCacheTimer);
        totalCacheTimer = null;
    }
}

async function loadFiles(path = "", options = {}) {
    return loadFilesPrepared(path, options);
}

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

/*groupingBtn.onclick = () => {
    groupingModal.classList.remove("hidden");
};*/
groupingBtn?.addEventListener("click", () => {
    groupingModal?.classList.remove("hidden");
});


/*closeGroupingBtn.onclick = () => {
    groupingModal.classList.add("hidden");
};*/
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
        const card = document.querySelector(`.card[data-path="${CSS.escape(path)}"]`);
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
    const card = document.querySelector(`.card[data-path="${CSS.escape(path)}"]`);
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

async function processMetadataQueue() {
    if (metadataRunning >= MAX_METADATA_REQUESTS) return;
    if (!METADATA_QUEUE.length) return;

    const batch = METADATA_QUEUE.splice(0, METADATA_BATCH_SIZE);
    metadataRunning++;

    try {
        const response = await secureFetch("/api/files/metadata/card-bulk", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify(batch)
        });

        if (response.ok) {
            const data = await response.json();

            Object.entries(data).forEach(([path, meta]) => {
                metadataCache.set(path, meta);
                applyCardMetadata(path, meta);
            });

            metadataProcessed += batch.length;

            if (metadataProcessed >= metadataTotal && METADATA_QUEUE.length === 0) {
                metadataTotal = 0;
                metadataProcessed = 0;
            }
        }

        await new Promise(resolve => setTimeout(resolve, 10));

    } catch (e) {
        console.error("Metadata queue failed", e);
    } finally {
        metadataRunning--;
        processMetadataQueue();
    }
}

function enqueueMetadata(paths) {
    const newPaths = paths.filter(path =>
        path &&
        !metadataLoaded.has(path + "_queued") &&
        !metadataCache.has(path)
    );

    if (!newPaths.length) return;

    for (const path of newPaths) {
        metadataLoaded.add(path + "_queued");
    }

    metadataTotal += newPaths.length;

    for (const path of newPaths) {
        if (!path) continue;

        if (metadataCache.has(path)) {
            applyCardMetadata(path, metadataCache.get(path));
            continue;
        }

        if (!METADATA_QUEUE.includes(path)) {
            METADATA_QUEUE.push(path);
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
            /*result = (a.lastModified || 0) - (b.lastModified || 0);*/
            result = (a.createdAt || a.lastModified || 0) - (b.createdAt || b.lastModified || 0);
            /* result = (a.createdAt || 0) - (b.createdAt || 0);*/
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
    /*previewBuildText.textContent = "Создание варианта просмотра...";*/
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

    return new Promise((resolve, reject) => {
        previewStatusTimer = setInterval(async () => {
            const statusResponse = await secureFetch(`/api/files/preview/status?previewId=${encodeURIComponent(currentPreviewId)}`);

            if (!statusResponse.ok) {
                clearInterval(previewStatusTimer);
                previewBuildModal.classList.add("hidden");
                reject(new Error("Preview status error"));
                return;
            }

            const status = await statusResponse.json();

            if (status.progress < 0) {
                clearInterval(previewStatusTimer);
                previewBuildModal.classList.add("hidden");
                reject(new Error("Preview creation failed"));
                return;
            }


            previewBuildSize.textContent =
                `Обработано: ${formatFileSize(status.size || 0)}`;
            if (status.ready) {
                clearInterval(previewStatusTimer);
                previewBuildModal.classList.add("hidden");

                resolve(`/api/files/preview/file?previewId=${encodeURIComponent(currentPreviewId)}`);
            }
        }, 1000);
    });
}

async function loadSharedLinksIndex() {
    if (PUBLIC_SHARE_MODE) return;

    try {
        const response = await secureFetch("/api/share");

        if (!response.ok) {
            sharedLinksByPath = new Map();
            return;
        }

        const links = await response.json();

        sharedLinksByPath = new Map();

        for (const link of links) {
            if (!link.path) continue;

            if (!sharedLinksByPath.has(link.path)) {
                sharedLinksByPath.set(link.path, []);
            }

            sharedLinksByPath.get(link.path).push(link);
        }

    } catch (e) {
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

/*function confirmMove() {
    if (!selectedMovePath && selectedMovePath !== "") {
        return;
    }

    const targetText = selectedMovePath ? "/" + selectedMovePath : "/";

    if (bulkMoveMode) {
        bulkMoveConfirmText.textContent =
            `Переместить ${selectedItems.size} выбранных объектов в папку ${targetText}?`;
    } else {
        bulkMoveConfirmText.textContent =
            `Переместить "${moveSourceName}" в папку ${targetText}?`;
    }

    bulkMoveConfirmModal.classList.remove("hidden");
}*/
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

/*async function confirmMove() {
    showLoadingGif();

    try {
    if (bulkMoveMode) {
        const count = selectedItems.size;
        const targetText = selectedMovePath ? "/" + selectedMovePath : "/";

        bulkMoveConfirmText.textContent =
            `Переместить ${count} выбранных объектов в папку ${targetText}?`;

        bulkMoveConfirmModal.classList.remove("hidden");
        return;
    }
    if (moveSourcePath == null) return;

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

    closeMoveModal();
    await loadFiles(currentPath);
    } finally {

        hideLoadingGif();
    }
}*/

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

            const index = uploadQueue.findIndex(t => t.id === id);
            if (index >= 0) uploadQueue.splice(index, 1);

            saveTransferTasks();
            renderTransferList();
            return;
        }

        // если задача уже грузится — ставим на паузу
        if (task.status === "uploading") {
            task.status = "paused";

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

            saveTransferTasks();
            renderTransferList();
            processUploadQueue();
            return;
        }
    }
});
/*totalCacheBtn.onclick = openTotalCacheModal;*/
totalCacheBtn?.addEventListener("click", openTotalCacheModal);

/*statusTotalCacheBtn.onclick = showTotalCacheStatus;
pauseTotalCacheBtn.onclick = pauseTotalCache;
resumeTotalCacheBtn.onclick = resumeTotalCache;*/

statusTotalCacheBtn?.addEventListener("click", showTotalCacheStatus);
pauseTotalCacheBtn?.addEventListener("click", pauseTotalCache);
resumeTotalCacheBtn?.addEventListener("click", resumeTotalCache);

// СКРЫТЬ — только закрывает модалку, процессы продолжаются
closeTotalCacheModalBtn.onclick = () => {
    if (totalCacheTimer) {
        clearInterval(totalCacheTimer);
        totalCacheTimer = null;
    }

    totalCacheModal.classList.add("hidden");
};

// ✕ — аварийно останавливает кеширование + анализ и закрывает модалку
abortTotalCacheBtn.onclick = () => {
    if (totalCacheTimer) {
        clearInterval(totalCacheTimer);
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

async function handleLoadMoreScroll() {
    if (!currentPreparedJobId || preparedAllLoaded || loading) return;

    const galleryBottom =
        gallery.scrollTop + gallery.clientHeight >= gallery.scrollHeight - 1200;

    const pageBottom =
        window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 1200;

    if (galleryBottom || pageBottom) {
        await loadPreparedPage(currentPreparedJobId);
    }
}

window.addEventListener("scroll", handleLoadMoreScroll);
gallery.addEventListener("scroll", handleLoadMoreScroll);
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
    const card = document.querySelector(`.card[data-path="${CSS.escape(path)}"]`);
    if (!card) return;

    const checkbox = card.querySelector(".item-checkbox");

    card.classList.toggle("selected", selected);

    if (checkbox) {
        checkbox.checked = selected;
    }
}

function saveTransferTasks() {
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

async function completeUploadSession(uploadId) {
    const form = new URLSearchParams();
    form.append("uploadId", uploadId);

    const response = await secureFetch("/api/files/upload/complete", {
        method: "POST",
        headers: {
            "Content-Type": "application/x-www-form-urlencoded"
        },
        body: form
    });

    if (!response.ok) {
        const text = await response.text();
        throw new Error("Failed to complete upload: " + text);
    }
}

async function initUploadSession(file, targetPath, chunkSize) {
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
        body: form
    });

    if (!response.ok) {
        throw new Error("Failed to init upload");
    }

    return await response.json();
}

async function getUploadStatus(uploadId) {
    const response = await secureFetch(
        `/api/files/upload/status?uploadId=${encodeURIComponent(uploadId)}`
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
        id: `upload_${file.name}_${file.size}_${file.lastModified}`,
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

        runUploadTask(task);
    }
}

async function runUploadTask(task) {
    const targetPath = task.targetPath;

    activeUploads++;
    task.running = true;
    task.status = "uploading";

    saveTransferTasks();
    renderTransferList();

    const isPaused = () => task.status === "paused";

    try {
        await uploadFileResumableManaged(task, isPaused);

        if (currentPath === targetPath) {
            await loadFiles(currentPath);
        }
    } catch (e) {
        if (e.message !== "Upload cancelled") {
            console.error("Upload error", e);
            task.status = "error";
            saveTransferTasks();
            renderTransferList();
        }
    } finally {
        task.running = false;
        activeUploads--;

        saveTransferTasks();
        renderTransferList();

        processUploadQueue();
    }
}

async function uploadFileResumableManaged(task, isPaused) {
    const file = task.file;
    const targetPath = task.targetPath;
    const CHUNK_SIZE = 1024 * 1024;

    /*const initData = await initUploadSession(file, targetPath, CHUNK_SIZE);

    const uploadId = initData.uploadId;
    task.uploadId = uploadId;
    task.totalChunks = initData.totalChunks;

    const totalChunks = initData.totalChunks;
    const uploadedChunks = new Set(initData.uploadedChunks || []);*/
    let initData = null;

    if (task.uploadId) {
        initData = await getUploadStatus(task.uploadId);
    }

    if (!initData) {
        initData = await initUploadSession(file, targetPath, CHUNK_SIZE);
        task.uploadId = initData.uploadId;
    }

    task.totalChunks = initData.totalChunks;

    const uploadId = task.uploadId;
    const totalChunks = initData.totalChunks;
    const uploadedChunks = new Set(initData.uploadedChunks || task.uploadedChunks || []);

    saveTransferTasks();
    for (let i = 0; i < totalChunks; i++) {
        if (uploadedChunks.has(i)) {
            const percent = Math.round(((i + 1) / totalChunks) * 100);
            task.progress = percent;
            task.status = "uploading";
            saveTransferTasks();
            updateTopProgress();
            continue;
        }
        if (cancelAllTransfers || task.status === "cancelled" || !transferTasks.has(task.id)) {
            throw new Error("Upload cancelled");
        }
        /*if (isPaused()) {
            return;
        }*/
        if (isPaused()) {
            task.status = "paused";
            saveTransferTasks();
            return;
        }

// 🔥 ВАЖНО — пауза ДО отправки чанка
        if (isPaused()) {
            while (isPaused()) {
                await new Promise(r => setTimeout(r, 200));
            }
        }
        const chunk = file.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE);

        const formData = new FormData();
        formData.append("file", chunk);
        formData.append("uploadId", uploadId);
        formData.append("chunkIndex", i);

        await sendChunk(formData);
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

        saveTransferTasks();
        updateTransferTaskUI(task);

    }

    await completeUploadSession(uploadId);

    task.progress = 100;
    task.status = "done";
    saveTransferTasks();
    renderTransferList();
    updateTopProgress();
}

function removeTransferTask(id) {
    const task = transferTasks.get(id);
    if (!task) return;

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
                <button class="transfer-remove" data-id="${task.id}">✕</button>
            </div>

            <div class="progress">
                <div class="progress-bar" style="width:${task.progress || 0}%"></div>
            </div>

            <div class="transfer-actions">
    <div class="transfer-size">
        ${formatFileSize(task.fileMeta?.size || task.size)}
    </div>

    ${
            task.status === "done" || task.status === "error"
                ? ""
                : `<button class="control ${task.status === "paused" ? "paused" : "playing"}" data-id="${task.id}">
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
    activeDownloads++;

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

    activeDownloads--;
    processDownloadQueue();
}

/*document.getElementById("clearTransfersBtn").onclick = async () => {*/
document.getElementById("clearTransfersBtn")?.addEventListener("click", async () => {
    cancelAllTransfers = true;

    uploadQueue.length = 0;
    downloadQueue.length = 0;
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
    /*};*/
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
            if (task.status !== "done") {
                task.status = "queued";
            }
        }

        transferTasks.set(task.id, task);
    }

    renderTransferList();
}

async function downloadResumableManaged(task) {
    const item = task.item;
    const key = "download_" + item.name;

    let start = Number(localStorage.getItem(key)) || 0;

    const response = await secureFetch(item.downloadUrl, {
        headers: {"Range": `bytes=${start}-`}
    });

    if (!response.ok && response.status !== 206) {
        throw new Error("Download failed");
    }

    if (!response.body) {
        throw new Error("ReadableStream is not available");
    }

    const reader = response.body.getReader();
    const chunks = [];
    let received = start;

    while (true) {

        if (cancelAllTransfers || task.status === "cancelled" || !transferTasks.has(task.id)) {
            throw new Error("Download cancelled");
        }

        const {done, value} = await reader.read();
        if (done) break;

        chunks.push(value);
        received += value.length;

        localStorage.setItem(key, received);

        const contentLength = Number(response.headers.get("Content-Length") || 0);
        const total = contentLength + start;
        const percent = total > 0 ? Math.round((received / total) * 100) : 0;

        task.progress = percent;
        task.status = "uploading";
        saveTransferTasks();
        renderTransferList();
        updateTopProgress();
    }

    if (item.type === "video" || (item.size && item.size > 50 * 1024 * 1024)) {
        const link = document.createElement("a");
        link.href = item.downloadUrl;
        link.download = item.name;
        document.body.appendChild(link);
        link.click();
        link.remove();
    } else {
        const blob = new Blob(chunks);
        const objectUrl = URL.createObjectURL(blob);

        const link = document.createElement("a");
        link.href = objectUrl;
        link.download = item.name;
        document.body.appendChild(link);
        link.click();
        link.remove();

        setTimeout(() => URL.revokeObjectURL(objectUrl), 5000);
    }

    task.progress = 100;
    task.status = "done";
    saveTransferTasks();
    renderTransferList();
    updateTopProgress();

    localStorage.removeItem(key);
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

    if (previewStatusTimer) {
        clearInterval(previewStatusTimer);
        previewStatusTimer = null;
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

    return new Promise((resolve, reject) => {
        previewStatusTimer = setInterval(async () => {
            const statusResponse = await secureFetch(
                `/api/files/preview/status?previewId=${encodeURIComponent(currentPreviewId)}`
            );

            if (!statusResponse.ok) {
                clearInterval(previewStatusTimer);
                previewStatusTimer = null;
                reject(new Error("Ошибка статуса preview"));
                return;
            }

            const status = await statusResponse.json();

            if (status.progress < 0) {
                clearInterval(previewStatusTimer);
                previewStatusTimer = null;
                reject(new Error("Ошибка подготовки preview"));
                return;
            }

            if (status.ready) {
                clearInterval(previewStatusTimer);
                previewStatusTimer = null;

                resolve(`/api/files/preview/file?previewId=${encodeURIComponent(currentPreviewId)}`);
            }
        }, 500);
    });
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

        window.addEventListener("mousemove", moveHandler);
        window.addEventListener("mouseup", upHandler);
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
    if (activeFolderPath !== currentPath) return;
    if (!items || items.length === 0) return;

    currentItems.push(...items);

    viewerItems = currentItems.filter(item =>
        !item.directory && (item.type === "image" || item.type === "video")
    );

    const fragment = document.createDocumentFragment();

    for (const item of items) {
        fragment.appendChild(createCard(item));
    }

    gallery.appendChild(fragment);
    requestAnimationFrame(() => {
        initLazyThumbs();
        initLazyMetadata();

        gallery.querySelectorAll("img.image-thumb, img.video-thumb-img").forEach(img => {
            if (!img.dataset.panEnabled) {
                img.dataset.panEnabled = "1";
                enablePreviewPan(img);
            }
        });

        updateBulkButtons();
        if (PUBLIC_SHARE_MODE) {
            updatePublicShareButtons(window.PUBLIC_SHARE_PERMISSION);
        }
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
    //metadataLoadingText.textContent = `Подготовка папки: ${progress || 0}%`;
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
                metadataLoaded.add(path);
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

    /*if (item.directory) {
        thumb.innerHTML = `<div class="folder-thumb">📁</div>`;
        thumb.addEventListener("click", () => loadFiles(item.relativePath));
    }*/
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

    /*let sizeText;
    if (item.directory) {
        sizeText = "Папка";
    }*/
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
    /*const dateText = "";*/
    /*const dateText = item.createdAt*/
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
    /*body.querySelector(".select-line").appendChild(selectBox);*/
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

    /*const line = body.querySelector(".select-line");

    line.addEventListener("click", (e) => {
        e.stopPropagation();

        if (e.target === selectBox) return;

        selectBox.checked = !selectBox.checked;
        selectBox.dispatchEvent(new Event("click"));
    });*/
    const actions = document.createElement("div");
    actions.className = "card-actions";

    const canPublicDownload =
        !PUBLIC_SHARE_MODE || !!item.downloadUrl;

    const canPublicDelete =
        !PUBLIC_SHARE_MODE;

    if (item.directory) {
        /*const openBtn = document.createElement("button");
        openBtn.textContent = "Открыть";*/
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
        /*actions.appendChild(openBtn);*/
        card.appendChild(openBtn);
    } else {
        const lower = item.name.toLowerCase();

        if (lower.endsWith(".insv") || lower.endsWith(".lrv")) {
            /*const downloadBtn = document.createElement("button");
            downloadBtn.textContent = "Скачать";
            downloadBtn.onclick = () => openDownloadFormatModal(item);
            actions.appendChild(downloadBtn);*/
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
                /*const downloadLink = document.createElement("a");
                downloadLink.href = item.downloadUrl;
                downloadLink.textContent = "Скачать";
                downloadLink.setAttribute("download", item.name);
                actions.appendChild(downloadLink);*/
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

    /*if (!PUBLIC_SHARE_MODE || window.PUBLIC_SHARE_PERMISSION === "MANAGE") {
        const deleteBtn = document.createElement("button");
        deleteBtn.className = "danger";
        deleteBtn.textContent = "Удалить";

        deleteBtn.onclick = () => openDeleteModal(item.relativePath, item.name);
        actions.appendChild(deleteBtn);
    }*/
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
        /*const shareBtn = document.createElement("button");
        shareBtn.textContent = "Поделиться";
        shareBtn.onclick = () => openShareModal(item);
        actions.appendChild(shareBtn);*/
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
        /*const propertiesBtn = document.createElement("button");
        propertiesBtn.textContent = "Свойства";


        propertiesBtn.onclick = async () => {
            currentPropertiesPath = item.relativePath;
            currentPropertiesName = item.name;

            await openPropertiesModal(item.relativePath);
        };
        propertiesBtn.onclick = async (e) => {
            e.preventDefault();
            e.stopPropagation();

            currentPropertiesPath = item.relativePath;
            currentPropertiesName = item.name;

            await openPropertiesModal(item.relativePath);
        };

        actions.appendChild(propertiesBtn);*/
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
    gallery.innerHTML = "";

    if (!items || items.length === 0) {
        gallery.innerHTML = `<div>Папка пуста</div>`;
        return;
    }
    currentItems = items;
    viewerItems = items.filter(item => !item.directory && (item.type === "image" || item.type === "video"));

    for (const item of items) {
        const card = createCard(item);
        gallery.appendChild(card);
    }

    initLazyThumbs();
    if (!PUBLIC_SHARE_MODE) {
        initLazyMetadata();
    }
    const imageThumbs = gallery.querySelectorAll("img.image-thumb");
    imageThumbs.forEach(img => enablePreviewPan(img));
    const videoThumbs = gallery.querySelectorAll("img.video-thumb-img");
    videoThumbs.forEach(img => enablePreviewPan(img));
    updateBulkButtons();
}

async function openPropertiesModal(path) {
    propertiesModal.classList.remove("hidden");
    propertiesBody.innerHTML = "Загрузка свойств...";

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

function initLazyMetadata() {
    const els = document.querySelectorAll(".card[data-path]");
    if (window.metadataObserver) {
        window.metadataObserver.disconnect();
    }
    const observer = new IntersectionObserver((entries, obs) => {

        const paths = [];

        for (const entry of entries) {
            if (!entry.isIntersecting) continue;

            const card = entry.target;
            const path = card.dataset.path;

            obs.unobserve(card);

            if (!path || metadataLoaded.has(path)) continue;

            metadataLoaded.add(path);
            paths.push(path);
        }

        if (paths.length) {
            enqueueMetadata(paths);
        }
    }, {
        root: gallery,
        rootMargin: "300px"
    });
    window.metadataObserver = observer;
    els.forEach(el => observer.observe(el));
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

function initLazyThumbs() {
    const images = document.querySelectorAll("img.lazy-thumb[data-src]");
    if (!images.length) return;

    const session = ++thumbSession;

    let loadedThumbs = 0;
    let loadingThumbs = 0;

    function onThumbLoaded() {
        if (session !== thumbSession) return;

        loadedThumbs++;

        if (loadingThumbs >= 3) {
            updateFolderLoadingRing(Math.round(loadedThumbs * 100 / loadingThumbs));
        }

        if (loadedThumbs >= loadingThumbs) {
            hideFolderLoadingRing();
        }
    }

    // 🔥 если много — показываем кольцо

    if (window.thumbObserver) {
        window.thumbObserver.disconnect();
    }

    const observer = new IntersectionObserver((entries, obs) => {
        for (const entry of entries) {
            if (!entry.isIntersecting) continue;

            const img = entry.target;
            const src = img.getAttribute("data-src");

            if (!src) {
                obs.unobserve(img);
                continue;
            }
            img.onload = () => {
                img.onload = null;
                img.onerror = null;
                onThumbLoaded();
            };

            img.onerror = () => {
                img.onload = null;
                img.onerror = null;

                img.src = img.classList.contains("video-thumb-img")
                    ? "/video-placeholder.png"
                    : "/image-placeholder.png";

                img.removeAttribute("data-src");

                onThumbLoaded();
            };
            loadingThumbs++;

            if (loadingThumbs === 3) {
                showFolderLoadingRing(0);
            }
            img.src = src;
            img.removeAttribute("data-src");
            if (session !== thumbSession) {
                obs.unobserve(img);
                return;
            }

            obs.unobserve(img);
        }
    }, {
        root: gallery,
        rootMargin: "500px"
    });

    window.thumbObserver = observer;
    images.forEach(img => observer.observe(img));
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

/*upBtn.addEventListener("click", () => loadFiles(parentPath || ""));*/
upBtn.addEventListener("click", async () => {
    const targetPath = parentPath || "";

    thumbSession++;
    folderLoadSession++;

    if (window.thumbObserver) window.thumbObserver.disconnect();
    if (window.metadataObserver) window.metadataObserver.disconnect();

    METADATA_QUEUE.length = 0;
    metadataRunning = 0;

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
    }, {passive: false});

    img.addEventListener("dblclick", () => {
        if (viewerZoom === 1) {
            viewerZoom = 2.5;
        } else {
            resetViewerZoom();
        }

        applyViewerZoom();
    });

    img.addEventListener("mousedown", (e) => {
        if (viewerZoom <= 1) return;

        viewerDragging = true;
        viewerDragStartX = e.clientX - viewerPanX;
        viewerDragStartY = e.clientY - viewerPanY;
    });

    window.addEventListener("mousemove", (e) => {
        if (!viewerDragging) return;

        viewerPanX = e.clientX - viewerDragStartX;
        viewerPanY = e.clientY - viewerDragStartY;

        applyViewerZoom();
    });

    window.addEventListener("mouseup", () => {
        viewerDragging = false;
    });

    img.addEventListener("touchstart", (e) => {
        if (e.touches.length === 2) {
            viewerLastTouchDistance = getTouchDistance(e.touches);
        }

        if (e.touches.length === 1 && viewerZoom > 1) {
            viewerDragging = true;
            viewerDragStartX = e.touches[0].clientX - viewerPanX;
            viewerDragStartY = e.touches[0].clientY - viewerPanY;
        }
    }, {passive: false});

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
    }, {passive: false});

    img.addEventListener("touchend", () => {
        viewerDragging = false;
        viewerLastTouchDistance = null;

        if (viewerZoom <= 1) {
            resetViewerZoom();
            applyViewerZoom();
        }
    });
}

function renderViewerItem() {
    if (viewerIndex < 0 || viewerIndex >= viewerItems.length) return;

    const item = viewerItems[viewerIndex];
    updateViewerSelectButton();
    if (currentPlayer) {
        currentPlayer.destroy();
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

    /*if (item.type === "image") {
        const lower = item.name.toLowerCase();

        const src =
            lower.endsWith(".heic") || lower.endsWith(".heif")
                ? `/api/files/image-thumbnail?path=${encodeURIComponent(item.relativePath)}`
                : item.previewUrl;


        resetViewerZoom();

        viewerBody.innerHTML = `
    <img id="viewerImage"
         class="viewer-image"
         src="${src}"
         alt="${escapeHtml(item.name)}">
`;

        initViewerImageZoom();
    }*/ else if (item.type === "video") {
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
            /* openDownloadFormatModal(item);*/
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
    if (previewStatusTimer) {
        clearInterval(previewStatusTimer);
        previewStatusTimer = null;
    }

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

    renderItems(currentItems);
    updateBulkButtons();
};
deleteModal.addEventListener("click", (e) => {
    if (e.target.classList.contains("delete-modal-backdrop")) {
        closeDeleteModal();
    }
});
clearSelectionBtn.onclick = async () => {
    selectedItems.clear();
    updateBulkButtons();
    await loadFiles(currentPath);
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

async function sendChunk(formData) {
    for (let attempt = 0; attempt < 3; attempt++) {
        try {
            const res = await secureFetch("/api/files/upload-chunk", {
                method: "POST",
                body: formData
            });

            if (!res.ok) throw new Error("upload error");
            return;
        } catch (e) {
            if (attempt === 2) throw e;
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
    return value
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

window.addEventListener("scroll", handleLoadMoreScroll);

/*Запрет масштабирования галереи*/
let lastTouchEnd = 0;

document.addEventListener("touchend", function (event) {
    const now = Date.now();
    if (now - lastTouchEnd <= 300) {
        event.preventDefault();
    }
    lastTouchEnd = now;
}, {passive: false});
document.addEventListener("gesturestart", function (e) {
    e.preventDefault();
});
document.addEventListener("gesturechange", function (e) {
    e.preventDefault();
});
document.addEventListener("gestureend", function (e) {
    e.preventDefault();
});
updateSortButtonsState();
updateBulkButtons();
