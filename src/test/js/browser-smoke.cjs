// Optional real Chromium smoke test. No running server or npm dependencies required.
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const {pathToFileURL} = require('node:url');
const assert = require('node:assert/strict');

const project = path.resolve(__dirname, '../../..');
const staticDir = path.join(project, 'src/main/resources/static');
const browser = process.env.BROWSER_PATH || [
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    '/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome'
].find(candidate => fs.existsSync(candidate));
if (!browser) throw new Error('Set BROWSER_PATH to a Chromium/Chrome/Edge executable');
const outDir = path.join(project, 'build/browser-smoke');
fs.mkdirSync(outDir, {recursive: true});
const script = fs.readFileSync(path.join(staticDir, 'app.js'), 'utf8');
const style = fs.readFileSync(path.join(staticDir, 'style.css'), 'utf8');

for (const [mode, width] of [['private', 1280], ['public', 1280], ['private', 390], ['public', 390]]) {
    const setup = `
        window.smokeErrors = [];
        window.addEventListener('error', event => smokeErrors.push(event.message));
        window.addEventListener('unhandledrejection', event => smokeErrors.push(String(event.reason)));
        localStorage.clear();
        window.PUBLIC_SHARE_MODE = ${mode === 'public'};
        window.SHARE_TOKEN = 'test';
        const pixel = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
        const testItems = [
            {name:'image.jpg',relativePath:'image.jpg',directory:false,type:'image',size:10,previewUrl:pixel,thumbnailUrl:pixel,downloadUrl:'/download'},
            {name:'folder',relativePath:'folder',directory:true,type:'directory',fileCount:1,folderCount:0}
        ];
        let indexStatusReads = 0;
        window.fetch = async url => {
            let data;
            if (url.includes('/api/photo-search/status')) data = {enabled:true,running:true,processed:++indexStatusReads,available:1,stage:'Обход фотографий',currentPath:'secret.jpg',error:'secret error'};
            else if (url.includes('/api/photo-search?')) data = {hits:[{item:testItems[0],description:'Дети на пляже'}],total:1,status:{enabled:true,available:1}};
            else if (url.includes('card-bulk')) data = {};
            else if (url.includes('prepare-folder')) data = {jobId:'job'};
            else if (url.includes('prepare-status')) data = {ready:true,total:2};
            else if (url.includes('prepared-items')) data = {items:testItems,total:2};
            else if (url.endsWith('/info')) data = {permission:'DOWNLOAD'};
            else data = [];
            return new Response(JSON.stringify(data), {status:200,headers:{'Content-Type':'application/json'}});
        };
    `;
    const checks = `
        window.addEventListener('DOMContentLoaded', async () => {
            const results = [];
            const check = (name, passed) => { results.push({name,passed}); if (!passed) throw new Error(name); };
            try {
                await new Promise(resolve => setTimeout(resolve,100));
                check('folder renders cards', document.querySelectorAll('#gallery .card').length===2);
                check('modals initially hidden', getComputedStyle(propertiesModal).display==='none');
                propertiesModal.classList.remove('hidden');
                check('properties panel positioned', getComputedStyle(propertiesModal.querySelector('.properties-content')).position==='absolute');
                check('properties panel fits viewport', propertiesModal.querySelector('.properties-content').getBoundingClientRect().width<=innerWidth);
                propertiesModal.classList.add('hidden');
                check('properties panel closes', getComputedStyle(propertiesModal).display==='none');
                openViewerByPath('image.jpg');
                check('image opens', !!document.getElementById('viewerImage'));
                check('viewer visible', getComputedStyle(viewer).display!=='none');
                await closeViewerModal();
                check('viewer closes', getComputedStyle(viewer).display==='none');
                if (!PUBLIC_SHARE_MODE) {
                    selectAllBtn.click();
                    check('select all preserves cards', selectedItems.size===2 && document.querySelectorAll('#gallery .card').length===2);
                    clearSelectionBtn.click();
                    check('clear selection', selectedItems.size===0 && !document.querySelector('#gallery .card.selected'));
                    document.getElementById('photoSearchOpen').click();
                    check('search modal opens', photoSearchModal.open && document.activeElement===photoSearchInput);
                    check('search input fits mobile viewport', photoSearchInput.getBoundingClientRect().right<=innerWidth && photoSearchInput.getBoundingClientRect().width>0);
                    photoSearchInput.value = 'отдых у моря';
                    document.getElementById('photoSearchReset').click();
                    check('clear query keeps input focused', photoSearchInput.value==='' && document.activeElement===photoSearchInput);
                    photoSearchInput.value = 'отдых у моря';
                    await runPhotoSearch();
                    await new Promise(resolve => setTimeout(resolve,50));
                    check('photo search displays compact result', document.querySelectorAll('#gallery .card').length===1
                        && !document.querySelector('.photo-search-description') && !!document.querySelector('.card-location-btn'));
                    const resultCard = document.querySelector('#gallery .card');
                    const cardRect = resultCard.getBoundingClientRect();
                    check('result actions fit card', [...resultCard.querySelectorAll('.card-location-btn,.card-download-btn,.card-properties-btn,.card-share-btn')].every(button => {
                        const rect = button.getBoundingClientRect();
                        return rect.left>=cardRect.left && rect.right<=cardRect.right;
                    }));
                    if (innerWidth<=768) {
                        check('checkbox stays below image', resultCard.querySelector('.item-checkbox').getBoundingClientRect().top>=resultCard.querySelector('.thumb').getBoundingClientRect().bottom);
                    }
                    check('search closes to show gallery', !photoSearchModal.open);
                    check('photo search supports viewer', viewerItems.length===1);
                    check('search form fits viewport', photoSearchInput.getBoundingClientRect().right<=innerWidth);
                    check('gallery below expanded header', gallery.getBoundingClientRect().top>=topbar.getBoundingClientRect().bottom);
                    topbar.classList.add("collapsed");
                    check("search button collapses with other actions", document.getElementById("photoSearchOpen").getBoundingClientRect().width===0);
                    topbar.classList.remove("collapsed");
                    syncPhotoSearchLayout();
                    openPhotoSearch();
                    await new Promise(resolve => setTimeout(resolve,50));
                    check("reopening refreshes running progress", !photoIndexProgress.hidden && photoIndexStatus.textContent.includes("Обработано за текущий запуск: 2"));
                    check("index details remain private", !photoIndexStatus.textContent.includes("secret"));
                    topbar.classList.remove("collapsed");
                    syncPhotoSearchLayout();
                    window.scrollTo(0,0);
                    check('gallery below indexing controls', gallery.getBoundingClientRect().top>=topbar.getBoundingClientRect().bottom);
                    closePhotoSearch();
                    await loadFiles('');
                    check('returns from search to folder', currentItems.length===2 && photoSearchState===null);
                } else {
                    check('download permission', getComputedStyle(downloadViewerBtn).display!=='none');
                    check('upload disabled', getComputedStyle(toggleTransfersBtn).display==='none');
                }
                check('no uncaught errors', smokeErrors.length===0);
            } catch(error) {
                results.push({name:String(error),passed:false,errors:smokeErrors});
            }
            const report=document.createElement('pre');
            report.id='smoke-results'; report.textContent=JSON.stringify(results);
            document.body.appendChild(report);
        });
    `;
    let html = fs.readFileSync(path.join(staticDir, mode === 'private' ? 'index.html' : 'share.html'), 'utf8');
    html = html.replace(/<script\b[\s\S]*?<\/script>/gi, '').replace(/<link\b[^>]*>/gi, '');
    html = html.replace('</head>', `<style>${style}</style></head>`);
    html = html.replace('</body>', `<script>${setup}</script><script>${script}</script><script>${checks}</script></body>`);
    const file = path.join(outDir, `${mode}-${width}.html`);
    fs.writeFileSync(file, html);
    const run = spawnSync(browser, [
        '--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
        '--disable-background-networking', '--disable-extensions',
        `--user-data-dir=${path.join(outDir, `profile-${mode}-${width}`)}`,
        `--window-size=${width},900`, '--virtual-time-budget=2000', '--dump-dom', pathToFileURL(file).href
    ], {encoding:'utf8', timeout:30000, maxBuffer:4*1024*1024, windowsHide:true});
    if (run.error) throw run.error;
    assert.equal(run.status, 0, run.stderr);
    fs.writeFileSync(path.join(outDir, `${mode}-${width}-dom.html`), run.stdout);
    const match = run.stdout.match(/<pre id="smoke-results">([\s\S]*?)<\/pre>/);
    assert.ok(match, `Missing ${mode} report: ${run.stderr}`);
    const results = JSON.parse(match[1].replaceAll('&quot;', '"').replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>'));
    assert.ok(results.every(result => result.passed), JSON.stringify(results, null, 2));
    console.log(`${mode} at ${width}px: ${results.length} browser checks passed`);
}
