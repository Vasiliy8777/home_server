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
        window.fetch = async url => {
            let data;
            if (url.includes('card-bulk')) data = {};
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
    const match = run.stdout.match(/<pre id="smoke-results">([\s\S]*?)<\/pre>/);
    assert.ok(match, `Missing ${mode} report: ${run.stderr}`);
    const results = JSON.parse(match[1].replaceAll('&quot;', '"').replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>'));
    assert.ok(results.every(result => result.passed), JSON.stringify(results, null, 2));
    console.log(`${mode} at ${width}px: ${results.length} browser checks passed`);
}
