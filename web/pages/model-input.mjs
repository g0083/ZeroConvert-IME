import { assistLine } from './assist-session.mjs';
import { readSettings, writeSettings } from './settings-store.mjs';

const editor = document.querySelector('#romaji');
const status = document.querySelector('#conversion-status');
const loader = document.querySelector('#loader');
const retryModel = document.querySelector('#retry-model');
const progressItems = new Map([...loader.querySelectorAll('[data-load-item]')]
    .map(item => [item.dataset.loadItem, item]));
const display = document.querySelector('#composition-display');
const assistStatus = document.querySelector('#assist-status');
const beforeCaret = document.querySelector('#composition-before');
const caret = document.querySelector('#composition-caret');
const afterCaret = document.querySelector('#composition-after');
const confirmed = document.querySelector('#confirmed');
const reading = document.querySelector('#reading');
const ruby = document.querySelector('#reading-ruby');
const assist = document.querySelector('#assist');
const editorButtons = document.querySelectorAll('[data-sample], [data-command]');
const frame = document.querySelector('#candidate-window');
const correctionSetting = document.querySelector('#enable-correction');
const predictionSetting = document.querySelector('#enable-prediction');
const learningSetting = document.querySelector('#learning-mode');
const clearLearning = document.querySelector('#clear-learning');
const learningStatus = document.querySelector('#learning-status');
const worker = new Worker(new URL('./model-worker.mjs', import.meta.url), { type: 'module' });
const { CorrectionDiff } = globalThis;
// The Worker reports assist candidate identities and learning capabilities in
// its ready message.
let assistIds = {};
let learning = null;
let settings = {};

let revision = 0;
let renderedRevision = 0;
let state = null;
let selection = null;
let frameReady = false;
let candidateHeight = 150;
let editing = false;
let canAdopt = false;
const session = 1; // The preview has one editor; blur does not clear its text.
const graphemes = new Intl.Segmenter('ja', { granularity: 'grapheme' });
const byteFormat = new Intl.NumberFormat('ja', { maximumFractionDigits: 1 });

function formatBytes(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 ** 2) return `${byteFormat.format(bytes / 1024)} KiB`;
    return `${byteFormat.format(bytes / 1024 ** 2)} MiB`;
}

function renderLoadProgress({ asset, phase, received, total }) {
    if (loader.dataset.state !== 'loading') return;
    const row = progressItems.get(asset);
    if (!row) return;
    const bar = row.querySelector('progress');
    const detail = row.querySelector('[data-detail]');
    row.dataset.state = phase;
    if (phase === 'downloading') {
        if (total) {
            bar.value = received / total;
            detail.textContent = `${Math.floor(received * 100 / total)}% · ${formatBytes(received)} / ${formatBytes(total)}`;
        } else {
            bar.removeAttribute('value');
            detail.textContent = received ? `${formatBytes(received)} 取得済み` : '取得を開始中';
        }
    } else if (phase === 'downloaded') {
        bar.value = 1;
        row.dataset.size = formatBytes(received);
        const stage = { engine: '準備中', dictionary: '展開中', model: '取得完了' }[asset];
        detail.textContent = `${stage} · ${row.dataset.size}`;
    } else if (phase === 'initializing') {
        bar.removeAttribute('value');
        detail.textContent = 'モデルを初期化中';
        status.textContent = 'ファイルの取得が完了し、モデルを初期化しています。';
    } else if (phase === 'warmup') {
        detail.textContent = '試し変換中';
        status.textContent = '試し変換を行っています。完了すると入力できます。';
    } else if (phase === 'done') {
        bar.value = 1;
        detail.textContent = row.dataset.size ? `完了 · ${row.dataset.size}` : '完了';
    }
}

function loadFailed(message) {
    loader.dataset.state = 'error';
    status.textContent = message;
    retryModel.hidden = false;
}

function send(key, value) {
    if (editor.disabled) return;
    canAdopt = false;
    renderAssist(null);
    worker.postMessage({ revision: ++revision, key, value, session });
}

function positionCandidates() {
    if (frame.hidden) return;
    const anchor = caret.getBoundingClientRect();
    const margin = 8;
    const width = frame.offsetWidth;
    const above = (assist.textContent ? Math.min(anchor.top, assist.getBoundingClientRect().top) : anchor.top) - 4;
    const below = anchor.bottom + 4;
    const roomBelow = window.innerHeight - margin - below;
    const roomAbove = above - margin;
    const placeAbove = roomBelow < candidateHeight && roomAbove > roomBelow;
    const height = Math.max(1, Math.min(candidateHeight, placeAbove ? roomAbove : roomBelow));
    const top = placeAbove ? above - height : below;
    frame.style.height = `${height}px`;
    frame.style.left = `${Math.max(margin, Math.min(anchor.left, window.innerWidth - width - margin))}px`;
    frame.style.top = `${Math.max(margin, Math.min(top, window.innerHeight - height - margin))}px`;
}

function candidates() {
    frame.hidden = !frameReady || !state?.candidates.length || !editing
        || (settings.showCandidatesAfterSpace && selection === null);
    if (frameReady) {
        const rows = state?.candidates ?? [];
        frame.contentWindow.zeroconRows = {
            revision: renderedRevision, ids: rows.map(row => row.id),
            forget: rows.map(row => learning?.forget && row.learningAllowed === true),
        };
        frame.contentWindow.updateCandidates(rows.map(row => row.text + row.suffix), selection ?? 0);
    }
    positionCandidates();
}

function render() {
    const selected = state?.candidates[selection ?? 0];
    const segments = [...graphemes.segment(state?.reading ?? '')];
    const offset = segments[state?.cursor]?.index ?? state?.reading.length ?? 0;
    if (selected) {
        const atEnd = state.cursor === segments.length;
        beforeCaret.textContent = selected.text + (atEnd ? selected.suffix : '');
        afterCaret.textContent = atEnd ? '' : selected.suffix;
    } else {
        beforeCaret.textContent = state?.reading.slice(0, offset) ?? '';
        afterCaret.textContent = state?.reading.slice(offset) ?? '';
    }
    reading.textContent = state?.reading ?? '';
    display.dataset.empty = String(!confirmed.textContent && !state?.reading);
    ruby.textContent = reading.textContent;
    ruby.hidden = !settings.showReading || !editing || selection !== null
        || !ruby.textContent || ruby.textContent === beforeCaret.textContent + afterCaret.textContent;
    if (editing && state?.appliedCorrection) {
        const { text, source } = state.appliedCorrection;
        renderAssist(text, source);
    }
    candidates();
}

// Assist text is shown beside the reading: a correction as the shared diff, a
// prediction as its continuation. Nothing here changes the composition text.
function renderAssist(text, diffSource) {
    if (!assist) return;
    assist.replaceChildren();
    if (!text) return;
    if (diffSource && CorrectionDiff) {
        const diff = CorrectionDiff.correctionDiff(diffSource, text);
        const original = document.createElement('span');
        original.className = 'assist-source';
        original.textContent = diff.original;
        const arrow = document.createElement('span');
        arrow.className = 'assist-arrow';
        arrow.textContent = ' → ';
        const replacement = document.createElement('span');
        replacement.innerHTML = diff.replacement;
        assist.append(original, arrow, replacement);
        return;
    }
    assist.textContent = text;
}

function attachCandidateWindow() {
    frameReady = typeof frame.contentWindow?.updateCandidates === 'function';
    if (frameReady) candidates();
}

frame.addEventListener('load', attachCandidateWindow);
attachCandidateWindow();

window.addEventListener('message', event => {
    if (event.origin !== location.origin || event.source !== frame.contentWindow || typeof event.data !== 'string') return;
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    if (message.type === 'candidate-ready') {
        attachCandidateWindow();
    } else if (message.type === 'resize' && Number.isFinite(message.height)) {
        candidateHeight = Math.min(450, Math.max(1, message.height));
        positionCandidates();
    } else if (['select', 'forget'].includes(message.type) && Number.isInteger(message.index)
        && message.revision === revision) {
        const selected = state?.candidates[message.index];
        if (selected && selected.id === message.id) send(message.type, { index: message.index, id: selected.id });
        editor.focus();
    }
});

worker.addEventListener('message', ({ data }) => {
    if (data.type === 'load-progress') {
        renderLoadProgress(data.progress);
    } else if (data.type === 'assist-load') {
        assistStatus.hidden = data.state === 'ready';
        assistStatus.textContent = data.state === 'failed'
            ? '補正・先読みを準備できませんでした。通常の変換は使えます。'
            : '補正・先読みを準備しています。';
    } else if (data.type === 'ready') {
        for (const asset of progressItems.keys()) renderLoadProgress({ asset, phase: 'done' });
        loader.dataset.state = 'ready';
        editor.disabled = false;
        for (const button of editorButtons) button.disabled = false;
        assistIds = data.assist ?? {};
        learning = data.learning ?? null;
        settings = readSettings(localStorage, { ...data.settings, learning: learning?.mode ?? 'disabled' });
        correctionSetting.checked = settings.correction;
        predictionSetting.checked = settings.prediction;
        learningSetting.value = settings.learning;
        for (const control of [correctionSetting, predictionSetting, learningSetting, clearLearning]) control.disabled = false;
        worker.postMessage({ key: 'settings', value: settings });
        status.textContent = '準備完了。ローマ字を入力できます。';
    } else if (data.type === 'failed') {
        loadFailed(`読み込みに失敗しました。${data.message}`);
    } else if (data.type === 'error' && data.revision === revision) {
        status.textContent = `変換に失敗しました。${data.message}`;
    } else if (data.type === 'state' && data.session === session && data.revision === revision) {
        ({ state, selection } = data);
        renderedRevision = revision;
        confirmed.textContent = data.committed;
        render();
    } else if (data.type === 'settings' && (data.revision === undefined || data.revision === revision)) {
        learning = data.learning;
        if (data.session === session) {
            ({ state, selection } = data);
            renderedRevision = revision;
            confirmed.textContent = data.committed;
            render();
        }
    } else if (data.type === 'assist' && editing && data.revision === revision) {
        const line = assistLine(data, assistIds, session, state?.reading);
        if (!line) return;
        // A key already sent to the Worker makes this result stale. Confirm an
        // automatic correction only after checking the page's latest revision.
        if (data.autoApply) {
            send('autoApply', { revision: data.revision, id: data.answer.id });
            return;
        }
        state = data.state;
        canAdopt = true;
        renderAssist(line?.text ?? null, line?.source ?? null);
        candidates();
    } else if (data.type === 'learning') {
        if (data.result === 'conflict') learningStatus.textContent = '別のタブで学習が更新されました。保存するにはこのページを再読み込みしてください。';
        else if (data.result === 'memoryOnly') learningStatus.textContent = '学習を保存できませんでした。このタブを閉じると、今回の変更は失われます。';
        else if (data.action === 'clear') learningStatus.textContent = 'このサイトの学習を消去しました。';
        else if (data.action === 'forget') learningStatus.textContent = '選んだ候補の学習を消去しました。';
    }
});
worker.addEventListener('error', () => loadFailed('変換エンジンを読み込めませんでした。'));

const keys = new Map([
    [' ', 'space'], ['ArrowUp', 'up'], ['ArrowDown', 'down'], ['ArrowLeft', 'left'], ['ArrowRight', 'right'],
    ['Enter', 'enter'], ['Escape', 'cancel'], ['Backspace', 'backspace'], ['Delete', 'delete'],
]);
editor.addEventListener('keydown', event => {
    if (event.isComposing || event.ctrlKey || event.altKey || event.metaKey) return;
    const key = keys.get(event.key);
    const index = selection !== null && /^[1-9]$/u.test(event.key) ? Number(event.key) - 1 : -1;
    const numbered = state?.candidates[index];
    if (event.shiftKey && state?.reading && (key === 'left' || key === 'right')) {
        send('adjustClause', key === 'left' ? -1 : 1);
    } else if (numbered) send('select', { index, id: numbered.id });
    else if (event.key === 'Tab' && !event.shiftKey && canAdopt) send('tab');
    else if (key) send(key);
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
editor.addEventListener('focus', () => {
    editing = true;
    render();
});
editor.addEventListener('blur', () => {
    editing = false;
    send('blur');
    render();
});
window.addEventListener('resize', positionCandidates);
window.addEventListener('scroll', positionCandidates, { capture: true, passive: true });
for (const button of editorButtons) {
    button.addEventListener('pointerdown', event => {
        if (document.activeElement === editor) event.preventDefault();
    });
    button.addEventListener('click', () => {
        editor.focus();
        if (button.dataset.sample) send('sample', button.dataset.sample);
        else send(button.dataset.command);
    });
}
retryModel.addEventListener('click', () => window.location.reload());
for (const control of [correctionSetting, predictionSetting, learningSetting]) {
    control.addEventListener('change', () => {
        settings = { ...settings, correction: correctionSetting.checked,
            prediction: predictionSetting.checked, learning: learningSetting.value };
        writeSettings(localStorage, settings);
        send('settings', settings);
    });
}
clearLearning.addEventListener('click', () => send('forgetAll'));
