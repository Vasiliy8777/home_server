let currentPath = "";
let parentPath = "";
let currentPlayer = null;

let deleteTargetPath = null;
let deleteTargetName = null;

let pendingResumeTaskId = null;

const transferPanel = document.getElementById("transferPanel");
const transferList = document.getElementById("transferList");
const resumeAllTransfersBtn = document.getElementById("resumeAllTransfersBtn");
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
    /*const controlBtn = e.target.closest(".control");*/
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
    for (const task of transferTasks.values()) {
        if (task.status === "paused" || task.status === "queued") {
            task.status = "queued";

            if (task.kind === "upload" && task.file && !uploadQueue.find(t => t.id === task.id)) {
                uploadQueue.push(task);
            }

            if (task.kind === "download" && !downloadQueue.find(t => t.id === task.id)) {
                downloadQueue.push(task);
            }
        }
    }

    saveTransferTasks();
    renderTransferList();
    processUploadQueue();
    processDownloadQueue();
};
const uploadQueue = [];
const downloadQueue = [];
const transferTasks = new Map();
const TRANSFERS_STORAGE_KEY = "gallery_transfer_tasks_v1";

let activeUploads = 0;
let activeDownloads = 0;

let cancelAllTransfers = false;

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

async function loadFiles(path = "") {
    const response = await fetch(`/api/files/list?path=${encodeURIComponent(path)}`);

    if (!response.ok) {
        alert("Не удалось загрузить список файлов");
        return;
    }

    const data = await response.json();

    currentPath = data.currentPath;
    parentPath = data.parentPath;
    currentPathEl.textContent = currentPath ? "/" + currentPath : "/";

    renderItems(data.items);
    updateNavButtons();
}

async function confirmDelete() {
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

function renderItems(items) {
    gallery.innerHTML = "";

    if (!items || items.length === 0) {
        gallery.innerHTML = `<div>Папка пуста</div>`;
        return;
    }

    viewerItems = items.filter(item => !item.directory && (item.type === "image" || item.type === "video"));

    for (const item of items) {
        const card = document.createElement("div");
        card.className = "card";

        const thumb = document.createElement("div");
        thumb.className = "thumb";

        if (item.directory) {
            thumb.innerHTML = `<div class="folder-thumb">📁</div>`;
            thumb.addEventListener("click", () => loadFiles(item.relativePath));
        } else if (item.type === "image") {
            thumb.innerHTML = `
                <img
                    loading="lazy"
                    data-src="${item.thumbnailUrl || item.previewUrl}"
                    alt="${escapeHtml(item.name)}"
                    class="lazy-thumb image-thumb"
                >
            `;
            thumb.addEventListener("click", () => openViewerByPath(item.relativePath));
        } else if (item.type === "video") {
            thumb.innerHTML = `
                <div class="video-thumb-wrap">
                    <img
                        loading="lazy"
                        data-src="${item.thumbnailUrl || '/video-placeholder.png'}"
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

        const sizeText = item.directory ? "Папка" : formatBytes(item.size);

        body.innerHTML = `
            <div class="file-name">${escapeHtml(item.name)}</div>
            <div class="meta">${sizeText}</div>
        `;

        const actions = document.createElement("div");
        actions.className = "card-actions";

        if (item.directory) {
            const openBtn = document.createElement("button");
            openBtn.textContent = "Открыть";
            openBtn.onclick = () => loadFiles(item.relativePath);
            actions.appendChild(openBtn);
        } else {
            const downloadLink = document.createElement("a");
            downloadLink.href = item.downloadUrl;
            downloadLink.textContent = "Скачать";
            downloadLink.setAttribute("download", item.name);
            actions.appendChild(downloadLink);
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

        body.appendChild(actions);
        card.appendChild(thumb);
        card.appendChild(body);
        gallery.appendChild(card);
    }

    initLazyThumbs();
    const imageThumbs = gallery.querySelectorAll("img.image-thumb");
    imageThumbs.forEach(img => enablePreviewPan(img));
    const videoThumbs = gallery.querySelectorAll("img.video-thumb-img");
    videoThumbs.forEach(img => enablePreviewPan(img));
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
        rootMargin: "200px"
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

    if (currentPlayer) {
        currentPlayer.destroy();
        currentPlayer = null;
    }

    if (item.type === "image") {
        viewerBody.innerHTML = `<img src="${item.previewUrl}" alt="${escapeHtml(item.name)}">`;
    } else if (item.type === "video") {
        viewerBody.innerHTML = `
            <video id="player"
                   controls
                   preload="metadata"
                   ${item.thumbnailUrl ? `poster="${item.thumbnailUrl}"` : ""}
                   style="width:100%; max-height:75vh;">
                <source src="${item.previewUrl}">
            </video>
        `;

        const video = document.getElementById("player");
        currentPlayer = new Plyr(video);
    }
    downloadViewerBtn.onclick = () => {
        const item = viewerItems[viewerIndex];
        addDownload(item);
    };
}

function showPrevItem() {
    if (!viewerItems.length) return;
    viewerIndex = (viewerIndex - 1 + viewerItems.length) % viewerItems.length;
    renderViewerItem();
}

function showNextItem() {
    if (!viewerItems.length) return;
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

function closeViewerModal() {
    if (currentPlayer) {
        currentPlayer.destroy();
        currentPlayer = null;
    }

    viewer.classList.add("hidden");
    viewerBody.innerHTML = "";
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

deleteModal.addEventListener("click", (e) => {
    if (e.target.classList.contains("delete-modal-backdrop")) {
        closeDeleteModal();
    }
});

folderListBtn.addEventListener("click", openFolderListModal);
closeFolderListModalBtn.addEventListener("click", closeFolderListModal);
cancelFolderListBtn.addEventListener("click", closeFolderListModal);
goToSelectedFolderBtn.addEventListener("click", goToSelectedFolder);

folderListModal.addEventListener("click", (e) => {
    if (e.target.classList.contains("move-modal-backdrop")) {
        closeFolderListModal();
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

async function confirmMove() {
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

loadFiles();