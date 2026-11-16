const editor = document.querySelector('#romaji');
const status = document.querySelector('#conversion-status');
const composition = document.querySelector('#composition');
const confirmed = document.querySelector('#confirmed');
const reading = document.querySelector('#reading');
const sample = document.querySelector('#sample');
const frame = document.querySelector('#candidate-window');
const worker = new Worker(new URL('./model-worker.mjs', import.meta.url), { type: 'module' });

let revision = 0;
let state = null;
let selection = null;
let frameReady = false;
let editing = false;

function send(key, value) {
    if (editor.disabled) return;
    worker.postMessage({ revision: ++revision, key, value });
}

function candidates() {
    frame.hidden = !frameReady || !state?.candidates.length || !editing;
    if (frameReady) {
        const rows = state?.candidates.map(row => row.text + row.suffix) ?? [];
        frame.contentWindow.updateCandidates(rows, selection ?? 0);
    }
}

function render() {
    const selected = state?.candidates[selection ?? 0];
    composition.textContent = selected ? selected.text + selected.suffix : state?.reading ?? '';
    reading.textContent = state?.reading ?? '';
    candidates();
}

window.addEventListener('message', event => {
    if (event.origin !== location.origin || event.source !== frame.contentWindow || typeof event.data !== 'string') return;
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    if (message.type === 'candidate-ready') {
        frameReady = true;
        candidates();
    } else if (message.type === 'resize' && Number.isFinite(message.height)) {
        frame.style.height = `${Math.min(450, Math.max(1, message.height))}px`;
    } else if (message.type === 'select' && Number.isInteger(message.index)) {
        const selected = state?.candidates[message.index];
        if (selected) send('select', { index: message.index, id: selected.id });
        editor.focus();
    }
});

worker.addEventListener('message', ({ data }) => {
    if (data.type === 'ready') {
        editor.disabled = false;
        sample.disabled = false;
        status.textContent = 'ZeroCon Model Nano v4.1 の変換エンジンを起動しました。';
    } else if (data.type === 'failed' || (data.type === 'error' && data.revision === revision)) {
        status.textContent = `変換エンジンでエラーが発生しました。${data.message}`;
    } else if (data.type === 'state' && data.revision === revision) {
        ({ state, selection } = data);
        confirmed.textContent = data.committed;
        render();
    }
});
worker.addEventListener('error', () => { status.textContent = '変換エンジンを読み込めませんでした。'; });

const keys = new Map([
    [' ', 'space'], ['ArrowUp', 'up'], ['ArrowDown', 'down'], ['ArrowLeft', 'left'], ['ArrowRight', 'right'],
    ['Enter', 'enter'], ['Escape', 'cancel'], ['Backspace', 'backspace'], ['Delete', 'delete'],
]);
editor.addEventListener('keydown', event => {
    if (event.isComposing || event.ctrlKey || event.altKey || event.metaKey) return;
    const key = keys.get(event.key);
    if (key) send(key);
    else if ([...event.key].length === 1) send('append', event.key);
    else return;
    event.preventDefault();
});
function insert(event) {
    if (event.isComposing) return;
    if (editor.value) send('append', editor.value.replaceAll('\n', ''));
    editor.value = '';
}
editor.addEventListener('input', insert);
editor.addEventListener('compositionend', insert);
editor.addEventListener('focus', () => { editing = true; candidates(); });
editor.addEventListener('blur', () => { editing = false; candidates(); });
sample.addEventListener('click', () => { editor.focus(); send('sample'); });
