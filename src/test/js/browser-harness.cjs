const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

class Element {
    constructor() {
        this.dataset = {};
        this.style = {};
        this.children = [];
        this.listeners = new Map();
        this.classes = new Set();
        this.classList = {
            add: (...names) => names.forEach(name => this.classes.add(name)),
            remove: (...names) => names.forEach(name => this.classes.delete(name)),
            contains: name => this.classes.has(name),
            toggle: (name, force = !this.classes.has(name)) => {
                force ? this.classes.add(name) : this.classes.delete(name);
                return force;
            }
        };
        this.value = '';
        this.textContent = '';
        this.innerHTML = '';
    }
    addEventListener(type, callback, options = {}) {
        if (options.signal?.aborted) return;
        const list = this.listeners.get(type) || new Set();
        list.add(callback);
        this.listeners.set(type, list);
        options.signal?.addEventListener('abort', () => list.delete(callback), {once: true});
    }
    removeEventListener(type, callback) { this.listeners.get(type)?.delete(callback); }
    querySelectorAll() { return []; }
    querySelector() { return null; }
    appendChild(child) { this.children.push(child); return child; }
    remove() { this.removed = true; }
    click() { this.clicked = true; }
    setAttribute(name, value) { this[name] = value; }
    getAttribute(name) { return this[name]; }
    removeAttribute(name) {
        if (name === 'data-src') delete this.dataset.src;
        delete this[name];
    }
    closest() { return null; }
}

function harness(publicMode = false) {
    const elements = new Map();
    const created = [];
    const timers = new Map();
    const frames = new Map();
    let nextId = 0;
    const window = new Element();
    window.innerWidth = 1200;
    window.innerHeight = 800;
    window.scrollY = 0;
    window.PUBLIC_SHARE_MODE = publicMode;
    window.SHARE_TOKEN = 'public-token';
    window.fetch = async () => { throw new Error('Unexpected fetch'); };
    const document = new Element();
    document.body = new Element();
    document.documentElement = new Element();
    document.documentElement.scrollHeight = 2000;
    document.hidden = false;
    document.cookie = 'XSRF-TOKEN=csrf%20token';
    document.getElementById = id => {
        if (!elements.has(id)) elements.set(id, new Element());
        return elements.get(id);
    };
    document.createElement = tag => {
        const el = new Element();
        el.tag = tag;
        created.push(el);
        return el;
    };
    document.createDocumentFragment = () => new Element();
    const storage = new Map();
    const observers = [];
    const context = vm.createContext({
        window, document, console: {log() {}, warn() {}, error() {}},
        localStorage: {
            getItem: key => storage.get(key) ?? null,
            setItem: (key, value) => storage.set(key, String(value)),
            removeItem: key => storage.delete(key)
        },
        AbortController, Blob, URLSearchParams, FormData, CSS: {escape: value => value},
        URL: {createObjectURL: () => 'blob:test', revokeObjectURL() {}},
        setTimeout: callback => { timers.set(++nextId, callback); return nextId; },
        clearTimeout: id => timers.delete(id),
        requestAnimationFrame: callback => { frames.set(++nextId, callback); return nextId; },
        cancelAnimationFrame: id => frames.delete(id),
        IntersectionObserver: class {
            constructor(callback, options) {
                this.callback = callback; this.options = options; this.targets = new Set();
                observers.push(this);
            }
            observe(el) { this.targets.add(el); }
            unobserve(el) { this.targets.delete(el); }
            disconnect() { this.targets.clear(); this.disconnected = true; }
        },
        alert() {}, confirm: () => true, navigator: {},
    });
    const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');
    vm.runInContext(source, context, {filename: 'app.js'});
    return {
        context, window, document, elements, created, timers, frames, storage, observers,
        run: code => vm.runInContext(code, context),
        async tick() {
            const [id, callback] = timers.entries().next().value;
            timers.delete(id);
            await callback();
        }
    };
}

module.exports = {harness, Element};
