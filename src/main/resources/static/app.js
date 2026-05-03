let currentPath = "";
let parentPath = "";
let currentPlayer = null;

let deleteTargetPath = null;
let deleteTargetName = null;

let currentItems = [];
let virtualStart = -1;
let virtualEnd = -1;
let virtualTotalCount = -1;
let virtualRenderScheduled = false;

const VIRTUAL_BUFFER_ROWS = window.innerWidth <= 768 ? 2 : 4;

let currentPreparedJobId = null;
let preparedAllLoaded = false;

/*const VIRTUAL_BUFFER = 30;*/
const CARD_APPROX_HEIGHT = 340;

let pendingResumeTaskId = null;
let bulkMoveMode = false;
const selectedItems = new Map();

let currentPreviewId = null;
let previewStatusTimer = null;

let sortField = localStorage.getItem("sortField") || "name";
let sortDirection = localStorage.getItem("sortDirection") || "asc";

let metadataLoaded = new Set();

let offset = 0;
/*const LIMIT = 500;*/
let lastLoadTime = 0;
let folderLoadSession = 0;
let activeFolderPath = "";
let backgroundFolderLoading = false;
let folderLoadAbortController = null;
let totalLoadedItems = 0;
let estimatedTotalItems = 0;

let loading = false;
let allLoaded = false;

const sortBtn = document.getElementById("sortBtn");
const sortModal = document.getElementById("sortModal");
const closeSortModalBtn = document.getElementById("closeSortModalBtn");
const sortDirectionBtn = document.getElementById("sortDirectionBtn");

const propertiesModal = document.getElementById("propertiesModal");
const propertiesBody = document.getElementById("propertiesBody");
const closePropertiesModalBtn = document.getElementById("closePropertiesModalBtn");

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

const metadataCache = new Map();

let pendingDownloadItem = null;
let pendingDownloadPreviewId = null;
let selectedDownloadFormat = "original";

let metadataTotal = 0;
let metadataProcessed = 0;
const METADATA_QUEUE = [];
let metadataRunning = 0;
const MAX_METADATA_REQUESTS = window.innerWidth <= 768 ? 2 : 4; // 🔥 для телефона критично
const METADATA_BATCH_SIZE = window.innerWidth <= 768 ? 20: 80;
let currentPreparedTotal = 0;
const PAGE_LIMIT = 1000;
async function loadFilesPrepared(path = "") {
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

    currentPathEl.textContent = currentPath ? "/" + currentPath : "/";

    showFolderLoadingRing(0);
    /*showMetadataLoadingModal();*/

    /*const res = await fetch(`/api/files/prepare-folder?path=${encodeURIComponent(pathForLoading)}`, {
        method: "POST"
    });*/
    const res = await fetch(
        `/api/files/prepare-folder?path=${encodeURIComponent(pathForLoading)}&sortField=${encodeURIComponent(sortField)}&sortDirection=${encodeURIComponent(sortDirection)}`,
        { method: "POST" }
    );

    const { jobId } = await res.json();

    if (sessionId !== folderLoadSession) return;
    let ready = false;

    while (!ready) {
        const statusRes = await fetch(`/api/files/prepare-status?jobId=${encodeURIComponent(jobId)}`);
        const status = await statusRes.json();
        currentPreparedTotal = status.total || 0;
        ready = status.ready;

        /*updateMetadataLoadingModal(
            status.progress || 0,
            status.processed || 0,
            status.total || 0
        );*/

        updateFolderLoadingRing(status.progress || 0);

        if (!ready) {
            await new Promise(r => setTimeout(r, 300));
        }
    }

    /*hideMetadataLoadingModal();*/
    currentPreparedJobId = jobId;

    await loadPreparedPage(jobId);

    /*setTimeout(handleLoadMoreScroll, 300);*/
    updateNavButtons();
}
async function loadPreparedPage(jobId) {
    if (preparedAllLoaded || loading) return;

    loading = true;

    try {
        const res = await fetch(
            /*`/api/files/prepared-items?jobId=${encodeURIComponent(jobId)}&offset=${offset}&limit=${LIMIT}`*/
            `/api/files/prepared-items?jobId=${encodeURIComponent(jobId)}&offset=${offset}&limit=${PAGE_LIMIT}`
        );

        if (!res.ok) return;

        const data = await res.json();
        const items = data.items || [];

        estimatedTotalItems = data.total || estimatedTotalItems || 0;

        if (items.length === 0) {
            setTimeout(() => maybeLoadMorePrepared(), 500);
            return;
        }

        appendItems(items);
        /*appendItems(sortItems(items));*/
        offset += items.length;

        if (estimatedTotalItems > 0 && offset >= estimatedTotalItems) {
            preparedAllLoaded = true;
            hideFolderLoadingRing();
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
async function loadFiles(path = "") {
    return loadFilesPrepared(path);
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

    const response = await fetch("/api/files/metadata/card-bulk", {
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

            if (metaEl.childNodes[0]) {
                metaEl.childNodes[0].textContent = hasContent
                    ? "Папка с файлами"
                    : "Пустая папка";
            }
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
        if (meta.directory) {
            if (!metaEl) return;
            if (meta.fileCount == null && meta.folderCount == null) {
                return;
            }

            const hasContent = (meta.fileCount || 0) > 0 || (meta.folderCount || 0) > 0;

            // 🔥 полностью перезаписываем текст
            metaEl.innerHTML = hasContent
                ? "Папка с файлами"
                : "Пустая папка";

            return;
        }
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
        const response = await fetch("/api/files/metadata/card-bulk", {
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
            /*metadataProcessed += Object.keys(data).length;*/
            metadataProcessed += batch.length;

            const percent = metadataTotal > 0
                ? Math.round(metadataProcessed * 100 / metadataTotal)
                : 100;

            updateMetadataLoadingModal(percent, metadataProcessed, metadataTotal);

            if (metadataProcessed >= metadataTotal && METADATA_QUEUE.length === 0) {
                setTimeout(() => {
                    hideMetadataLoadingModal();
                    metadataTotal = 0;
                    metadataProcessed = 0;
                }, 500);
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
    showMetadataLoadingModal();

    updateMetadataLoadingModal(
        metadataTotal > 0 ? Math.round(metadataProcessed * 100 / metadataTotal) : 0,
        metadataProcessed,
        metadataTotal
    );
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

/*function enqueueMetadata(paths) {
    METADATA_QUEUE.push(...paths);
    processMetadataQueue();
}*/
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

    bulkMoveBtn.disabled = count === 0;
    bulkDeleteBtn.disabled = count === 0;
    clearSelectionBtn.disabled = count === 0;
    bulkDownloadBtn.disabled = count === 0;

    bulkDownloadBtn.textContent = count ? `Скачать (${count})` : "Скачать выделенные";
    bulkMoveBtn.textContent = count ? `Переместить (${count})` : "Переместить выбранные";
    bulkDeleteBtn.textContent = count ? `Удалить (${count})` : "Удалить выбранные";
}

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

    const startResponse = await fetch("/api/files/preview/start", {
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
            const statusResponse = await fetch(`/api/files/preview/status?previewId=${encodeURIComponent(currentPreviewId)}`);

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

function executeBulkDownload() {
    if (!selectedItems.size) return;

    const params = new URLSearchParams();

    for (const item of selectedItems.values()) {
        params.append("paths", item.path);
    }

    const link = document.createElement("a");
    link.href = `/api/files/download-selected?${params.toString()}`;
    link.download = "selected-files.zip";

    document.body.appendChild(link);
    link.click();
    link.remove();

    bulkDownloadModal.classList.add("hidden");
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

async function confirmMove() {
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

    const response = await fetch("/api/files/move", {
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
}

async function executeBulkMove() {
    const count = selectedItems.size;

    for (const item of selectedItems.values()) {
        const formData = new URLSearchParams();
        formData.append("sourcePath", item.path);
        formData.append("targetPath", selectedMovePath);

        const response = await fetch("/api/files/move", {
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
//gallery.addEventListener("scroll", handleLoadMoreScroll);
const currentPathEl = document.getElementById("currentPath");
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

    const response = await fetch("/api/files/upload/complete", {
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

    const response = await fetch("/api/files/upload/init", {
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

    const initData = await initUploadSession(file, targetPath, CHUNK_SIZE);

    const uploadId = initData.uploadId;
    task.uploadId = uploadId;
    task.totalChunks = initData.totalChunks;

    const totalChunks = initData.totalChunks;
    const uploadedChunks = new Set(initData.uploadedChunks || []);

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
        if (isPaused()) {
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

document.getElementById("clearTransfersBtn").onclick = async () => {
    cancelAllTransfers = true;

    uploadQueue.length = 0;
    downloadQueue.length = 0;
    transferTasks.clear();

    renderTransferList();
    transferPanel.classList.add("hidden");
    localStorage.removeItem(TRANSFERS_STORAGE_KEY);

    try {
        await fetch("/api/files/clear-temp", {
            method: "DELETE"
        });
    } catch (e) {
        console.error("Ошибка очистки temp:", e);
    }

    setTimeout(() => {
        cancelAllTransfers = false;
    }, 300);
};

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

    const response = await fetch(item.downloadUrl, {
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

    const response = await fetch("/api/files/folders/tree");
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
            await fetch(`/api/files/preview/cancel?previewId=${encodeURIComponent(id)}`, {
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

    const resp = await fetch("/api/files/preview/rename-original-start", {
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
            const statusResponse = await fetch(
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
/*async function fillMobileStartPages(jobId = currentPreparedJobId) {
    if (!jobId || jobId !== currentPreparedJobId || preparedAllLoaded) return;

    let guard = 0;

    while (
        jobId === currentPreparedJobId &&
        !preparedAllLoaded &&
        !loading &&
        gallery.scrollHeight <= gallery.clientHeight + 200 &&
        guard < 3
        ) {
        lastLoadTime = 0;
        await loadPreparedPage(jobId);
        scheduleVirtualRender();

        guard++;
        await new Promise(r => setTimeout(r, 250));
    }
}*/
function appendItems(items) {
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

function updateMetadataLoadingModal(progress, processed, total) {
    metadataLoadingBar.style.width = `${progress || 0}%`;
    metadataLoadingCount.textContent = `${processed || 0} из ${total || 0} файлов`;
    metadataLoadingText.textContent = `Подготовка папки: ${progress || 0}%`;
}

function hideMetadataLoadingModal() {
    if (!metadataLoadingModal) return;
    metadataLoadingModal.classList.add("hidden");
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

/*async function loadMoreFilesInBackground(pathForLoading, sessionId) {
    if (loading || allLoaded) return;

    loading = true;

    try {
        const response = await fetch(
            `/api/files/list?path=${encodeURIComponent(pathForLoading)}&offset=${offset}&limit=${LIMIT}`,
            {signal: folderLoadAbortController.signal}
        );

        if (sessionId !== folderLoadSession || currentPath !== pathForLoading) return;

        if (!response.ok) return;

        const data = await response.json();

        if (sessionId !== folderLoadSession || currentPath !== pathForLoading) return;

        const items = sortItems(data.items || []);
        appendItems(items);

        offset += items.length;
        totalLoadedItems += items.length;

        const percent = estimatedTotalItems > 0
            ? Math.min(100, Math.round((totalLoadedItems / estimatedTotalItems) * 100))
            : 0;

        updateFolderLoadingRing(percent);

        if (items.length < LIMIT) {
            allLoaded = true;
            updateFolderLoadingRing(100);
        }
    } finally {
        loading = false;
    }
}*/

/*async function loadMoreFiles() {
    const resp = await fetch(`/api/files/list?path=${encodeURIComponent(currentPath)}&offset=${offset}&limit=${LIMIT}`);

    if (!resp.ok) {
        loading = false;
        return;
    }

    const data = await resp.json();
    const items = data.items || [];

    appendItems(sortItems(items));

    offset += items.length;

    if (items.length < LIMIT) {
        allLoaded = true;
    }

    loading = false;
}*/

function showFolderLoadingRing(percent = 0) {
    const ring = document.getElementById("folderLoadingRing");
    if (!ring) return;

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

    setTimeout(() => {
        ring.classList.add("hidden");
    }, 400);
}

/*async function startBackgroundFolderLoading(pathForLoading, sessionId) {
    if (backgroundFolderLoading) return;

    backgroundFolderLoading = true;

    try {
        while (
            !allLoaded &&
            sessionId === folderLoadSession &&
            currentPath === pathForLoading
            ) {
            await loadMoreFilesInBackground(pathForLoading, sessionId);
            const delay = window.innerWidth <= 768 ? 350 : 120;
            await new Promise(resolve => setTimeout(resolve, delay));
        }
    } catch (e) {
        if (e.name !== "AbortError") {
            console.error("Background folder loading failed", e);
        }
    } finally {
        backgroundFolderLoading = false;

        if (sessionId === folderLoadSession && currentPath === pathForLoading) {
            hideFolderLoadingRing();
        }
    }
}

function updateItemsMetadata(metadataMap) {
    Object.entries(metadataMap).forEach(([path, createdAt]) => {

        const card = document.querySelector(`.file-card[data-path="${CSS.escape(path)}"]`);
        if (!card) return;

        const meta = card.querySelector(".meta");
        if (!meta) return;

        const size = meta.dataset.size;

        meta.textContent = `${size} · ${formatDateTime(createdAt)}`;
    });
}*/

async function confirmDelete() {
    if (selectedItems.size > 0 && deleteTargetPath == null) {
        for (const item of selectedItems.values()) {
            const response = await fetch(`/api/files?path=${encodeURIComponent(item.path)}`, {
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

    const response = await fetch(`/api/files?path=${encodeURIComponent(deleteTargetPath)}`, {
        method: "DELETE"
    });

    if (!response.ok) {
        const text = await response.text();
        alert("Ошибка удаления:\n" + text);
        return;
    }

    closeDeleteModal();
    await loadFiles(currentPath);
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

    const selectBox = document.createElement("input");
    selectBox.type = "checkbox";
    selectBox.className = "item-checkbox";
    selectBox.checked = selectedItems.has(item.relativePath);

    card.classList.toggle("selected", selectedItems.has(item.relativePath));
    const thumb = document.createElement("div");
    thumb.className = "thumb";

    if (item.directory) {
        thumb.innerHTML = `<div class="folder-thumb">📁</div>`;
        thumb.addEventListener("click", () => loadFiles(item.relativePath));
    } else if (item.type === "image") {
        thumb.innerHTML = `
    <img
        src="/image-placeholder.png"   // 👈 сразу показываем
        loading="lazy"
        data-src="${buildImageThumbnailUrl(item.relativePath)}"
        alt="${escapeHtml(item.name)}"
        class="lazy-thumb image-thumb"
    >
`;
        thumb.addEventListener("click", () => openViewerByPath(item.relativePath));
    } else if (item.type === "video") {
        thumb.innerHTML = `
    <div class="video-thumb-wrap">
        <img
            src="/video-placeholder.png"  // 👈 сразу показываем
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

    let sizeText; if (item.directory) {
        sizeText = "Папка";
    }else {
        sizeText = formatBytes(item.size);
    }
    /*const dateText = "";*/
    const dateText = item.createdAt
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
    body.querySelector(".select-line").appendChild(selectBox);
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

    const line = body.querySelector(".select-line");

    line.addEventListener("click", (e) => {
        e.stopPropagation();

        if (e.target === selectBox) return;

        selectBox.checked = !selectBox.checked;
        selectBox.dispatchEvent(new Event("click"));
    });
    const actions = document.createElement("div");
    actions.className = "card-actions";

    if (item.directory) {
        const openBtn = document.createElement("button");
        openBtn.textContent = "Открыть";
        openBtn.onclick = () => loadFiles(item.relativePath);
        actions.appendChild(openBtn);
    } else {
        const lower = item.name.toLowerCase();

        if (lower.endsWith(".insv") || lower.endsWith(".lrv")) {
            const downloadBtn = document.createElement("button");
            downloadBtn.textContent = "Скачать";
            downloadBtn.onclick = () => openDownloadFormatModal(item);
            actions.appendChild(downloadBtn);
        } else {
            const downloadLink = document.createElement("a");
            downloadLink.href = item.downloadUrl;
            downloadLink.textContent = "Скачать";
            downloadLink.setAttribute("download", item.name);
            actions.appendChild(downloadLink);
        }
    }
    const moveBtn = document.createElement("button");
    moveBtn.textContent = "Переместить";
    moveBtn.onclick = () => openMoveModal(item.relativePath, item.name);
    actions.appendChild(moveBtn);

    const deleteBtn = document.createElement("button");
    deleteBtn.className = "danger";
    deleteBtn.textContent = "Удалить";
    //deleteBtn.onclick = () => removeItem(item.relativePath, item.name);
    deleteBtn.onclick = () => openDeleteModal(item.relativePath, item.name);
    actions.appendChild(deleteBtn);

    const propertiesBtn = document.createElement("button");
    propertiesBtn.textContent = "Свойства";
    propertiesBtn.onclick = () => openPropertiesModal(item.relativePath);
    actions.appendChild(propertiesBtn);

    body.appendChild(actions);
    card.appendChild(thumb);
    card.appendChild(body);

    return card;
}

function buildImageThumbnailUrl(path) {
    if (!path) return "/image-placeholder.png";
    return `/api/files/image-thumbnail?path=${encodeURIComponent(path)}`;
}

function buildVideoStreamUrl(path) {
    return `/api/files/stream?path=${encodeURIComponent(path)}`;
}

function buildVideoThumbnailUrl(path) {
    if (!path) return "/video-placeholder.png";
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
    initLazyMetadata();
    const imageThumbs = gallery.querySelectorAll("img.image-thumb");
    imageThumbs.forEach(img => enablePreviewPan(img));
    const videoThumbs = gallery.querySelectorAll("img.video-thumb-img");
    videoThumbs.forEach(img => enablePreviewPan(img));
    updateBulkButtons();
}

async function openPropertiesModal(path) {
    propertiesModal.classList.remove("hidden");
    propertiesBody.innerHTML = "Загрузка свойств...";

    const response = await fetch(`/api/files/properties?path=${encodeURIComponent(path)}`);

    if (!response.ok) {
        propertiesBody.innerHTML = "Ошибка загрузки";
        return;
    }

    const data = await response.json();

    propertiesBody.innerHTML = renderFullProperties(data);

    if (data.type === "folder") {
        propertiesBody.innerHTML += `<div id="folderStatsBlock">Считаем размер папки...</div>`;

        const statsResponse = await fetch(`/api/files/properties/folder-stats?path=${encodeURIComponent(path)}`);

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

    const observer = new IntersectionObserver((entries, obs) => {
        for (const entry of entries) {
            if (!entry.isIntersecting) continue;

            const img = entry.target;
            const src = img.getAttribute("data-src");

            if (src) {
                img.onerror = () => {
                    img.onerror = null;

                    if (img.classList.contains("video-thumb-img")) {
                        img.src = "/video-placeholder.png";
                    } else {
                        img.src = "/image-placeholder.png";
                    }

                    img.removeAttribute("data-src");
                };

                img.src = src;
                img.removeAttribute("data-src");
            }

            obs.unobserve(img);
        }
    }, {
        rootMargin: "400px"
    });

    images.forEach(img => observer.observe(img));
}

async function removeItem(path, name) {
    if (!confirm(`Удалить "${name}"?`)) return;

    const response = await fetch(`/api/files?path=${encodeURIComponent(path)}`, {
        method: "DELETE"
    });

    if (!response.ok) {
        alert("Ошибка удаления");
        return;
    }

    await loadFiles(currentPath);
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

upBtn.addEventListener("click", () => loadFiles(parentPath || ""));

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
    const name = createFolderInput.value.trim();
    if (!name) {
        alert("Введите имя папки");
        return;
    }

    const formData = new URLSearchParams();
    formData.append("name", name);

    const response = await fetch(`/api/files/folder?path=${encodeURIComponent(currentPath)}`, {
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
}


function openViewerByPath(relativePath) {
    viewerIndex = viewerItems.findIndex(item => item.relativePath === relativePath);
    if (viewerIndex === -1) return;
    renderViewerItem();
    viewer.classList.remove("hidden");
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
        viewerBody.innerHTML = `<img src="${item.previewUrl}" alt="${escapeHtml(item.name)}">`;
    } else if (item.type === "video") {
        const lower = item.name.toLowerCase();
        if (lower.endsWith(".lrv") || lower.endsWith(".insv")) {
            viewerBody.innerHTML = `<div>Подготовка видео...</div>`;

            downloadViewerBtn.onclick = () => {
                openDownloadFormatModal(item, currentPreviewId);
            };

            openRenamePreview(item)
                .then(url => {
                    if (!url) {
                        viewerBody.innerHTML = `<div>Не удалось подготовить видео</div>`;
                        return;
                    }

                    viewerBody.innerHTML = `
                <video id="player"
                       controls
                       preload="auto"
                       poster="${buildVideoThumbnailUrl(item.relativePath)}"
                       style="width:100%; max-height:75vh;">
                    <source src="${url}" type="video/mp4">
                </video>
            `;

                    const video = document.getElementById("player");
                    currentPlayer = new Plyr(video);
                })
                .catch(e => {
                    console.error(e);
                    viewerBody.innerHTML = `<div>Не удалось подготовить видео</div>`;
                });

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
closePropertiesModalBtn.onclick = () => {
    propertiesModal.classList.add("hidden");
};
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

        const response = await fetch("/api/files/download/mp4-start", {
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
            fetch(`/api/files/preview/cancel?previewId=${encodeURIComponent(data.previewId)}`, {
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
        await fetch(`/api/files/preview/cancel?previewId=${encodeURIComponent(currentPreviewId)}`, {
            method: "DELETE"
        });
    }

    currentPreviewId = null;
    previewBuildModal.classList.add("hidden");
};

async function showPrevItem() {
    if (!viewerItems.length) return;

    if (currentPlayer) {
        currentPlayer.destroy();
        currentPlayer = null;
    }

    viewerBody.innerHTML = ""; // 👈 важно

    await cleanupCurrentPreview();

    viewerIndex = (viewerIndex - 1 + viewerItems.length) % viewerItems.length;
    renderViewerItem();
}

async function showNextItem() {
    if (!viewerItems.length) return;

    if (currentPlayer) {
        currentPlayer.destroy();
        currentPlayer = null;
    }

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
    if (currentPlayer) {
        currentPlayer.destroy();
        currentPlayer = null;
    }

    viewerBody.innerHTML = ""; // 👈 ВАЖНО

    await cleanupCurrentPreview(); // 👈 теперь безопасно

    viewer.classList.add("hidden");
    viewerIndex = -1;
}


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
    bulkDownloadModal.classList.add("hidden");
};

bulkDownloadModal.addEventListener("click", (e) => {
    if (e.target.classList.contains("delete-modal-backdrop")) {
        bulkDownloadModal.classList.add("hidden");
    }
});
bulkMoveBtn.onclick = () => {
    if (!selectedItems.size) return;

    bulkMoveMode = true;
    selectedMovePath = "";

    moveModalTargetName.textContent = `Перемещаем объектов: ${selectedItems.size}`;
    selectedMovePathEl.textContent = `Выбрано: /`;
    folderTreeContainer.innerHTML = `<div>Загрузка папок...</div>`;

    moveModal.classList.remove("hidden");

    fetch("/api/files/folders/tree")
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
confirmBulkMoveBtn.onclick = executeBulkMove;

cancelBulkMoveBtn.onclick = () => {
    bulkMoveConfirmModal.classList.add("hidden");
};

bulkMoveConfirmModal.addEventListener("click", (e) => {
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
confirmMoveBtn.addEventListener("click", confirmMove);

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
    loadFiles(""); // переход в корень
});

viewer.addEventListener("click", (e) => {
    if (e.target.classList.contains("viewer-backdrop")) {
        closeViewerModal();
    }
});

document.addEventListener("keydown", (e) => {
    if (viewer.classList.contains("hidden")) return;

    if (e.key === "Escape") closeViewerModal();
    if (e.key === "ArrowLeft") showPrevItem();
    if (e.key === "ArrowRight") showNextItem();
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
    moveSourcePath = sourcePath;
    moveSourceName = sourceName;
    selectedMovePath = "";

    moveModalTargetName.textContent = `Перемещаем: ${sourceName}`;
    selectedMovePathEl.textContent = `Выбрано: /`;
    folderTreeContainer.innerHTML = `<div>Загрузка папок...</div>`;

    moveModal.classList.remove("hidden");

    const response = await fetch("/api/files/folders/tree");
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
            const res = await fetch("/api/files/upload-chunk", {
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


function renderFolderTree(node) {
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

    row.addEventListener("click", () => {
        selectedMovePath = node.relativePath || "";
        selectedMovePathEl.textContent = `Выбрано: ${selectedMovePath ? "/" + selectedMovePath : "/"}`;

        document.querySelectorAll(".folder-tree-row.selected").forEach(el => {
            el.classList.remove("selected");
        });
        row.classList.add("selected");
    });

    wrapper.appendChild(row);
    if (hasChildren) {
        const childrenContainer = document.createElement("div");
        childrenContainer.className = "folder-children hidden"; // <-- сразу скрыто
        toggle.textContent = "▶"; // <-- стрелка вправо

        for (const child of node.children) {
            childrenContainer.appendChild(renderFolderTree(child));
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

function closeMoveModal() {
    moveModal.classList.add("hidden");
    moveSourcePath = null;
    moveSourceName = null;
    selectedMovePath = "";
    folderTreeContainer.innerHTML = "";
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
//window.addEventListener("scroll", debounce(loadVisibleMetadata, 200));
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
}, { passive: false });
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
