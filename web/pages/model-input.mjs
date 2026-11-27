import { assistLine } from './assist-session.mjs';
import { androidVibrationDefault, createAndroidKeyboard, defaultPresentation } from './android-keyboard.mjs';
import { readSettings, writeSettings } from './settings-store.mjs';

const editor = document.querySelector('#romaji');
const editorLabel = document.querySelector('label[for="romaji"]');
const demo = document.querySelector('#demo');
const presentationPicker = document.querySelector('#demo-presentation');
const androidRoot = document.querySelector('#android-keyboard');
const status = document.querySelector('#conversion-status');
const loader = document.querySelector('#loader');
const retryModel = document.querySelector('#retry-model');
const progressItems = new Map([...loader.querySelectorAll('[data-load-item]')]
    .map(item => [item.dataset.loadItem, item]));
const progressModelLabel = document.querySelector('#progress-model-label');
const heroModelBadge = document.querySelector('#hero-model-badge');
const telemetryModel = document.querySelector('#telemetry-model');
const telemetryLatency = document.querySelector('#telemetry-latency');
const telemetryCache = document.querySelector('#telemetry-cache');
const display = document.querySelector('#composition-display');
const assistStatus = document.querySelector('#assist-status');
const beforeCaret = document.querySelector('#composition-before');
const caret = document.querySelector('#composition-caret');
const afterCaret = document.querySelector('#composition-after');
const confirmed = document.querySelector('#confirmed');
const reading = document.querySelector('#reading');
const editorButtons = document.querySelectorAll('[data-sample], [data-command]');
const frame = document.querySelector('#candidate-window');
const readingFrame = document.querySelector('#reading-window');
const correctionSetting = document.querySelector('#enable-correction');
const predictionSetting = document.querySelector('#enable-prediction');
const vibrationSetting = document.querySelector('#android-vibration');
const learningSetting = document.querySelector('#learning-mode');
const clearLearning = document.querySelector('#clear-learning');
const learningStatus = document.querySelector('#learning-status');
const worker = new Worker(new URL('./model-worker.mjs', import.meta.url), { type: 'module' });
// Bound each worker task even if a long burst of typing is queued.
const MAX_QUEUED_APPEND = 64;
// The Worker reports assist candidate identities and learning capabilities in
// its ready message.
let assistIds = {};
let learning = null;
let settings = {};

let revision = 0;
let renderedRevision = 0;
let inFlightRevision = null;
const pendingEdits = [];
let committedUpdate;
let state = null;
let selection = null;
let frameReady = false;
let candidateHeight = 150;
let editing = false;
let assistOffer = null;
let readingValue = '';
let readingRequest = 0;
const session = 1; // The preview has one editor; blur does not clear its text.
const graphemes = new Intl.Segmenter('ja', { granularity: 'grapheme' });
const byteFormat = new Intl.NumberFormat('ja', { maximumFractionDigits: 1 });
const renderAndroidKeyboard = createAndroidKeyboard(androidRoot, (key, value) => {
    if (key === 'select' && (renderedRevision !== revision || state?.candidates[value.index]?.id !== value.id)) return;
    editor.focus({ preventScroll: true });
    send(key, value);
}, () => settings.vibration);
let presentation;

function updateReadyMessage() {
    status.textContent = presentation === 'android' ? '準備完了。画面のキーボードで入力できます。' : '準備完了。ローマ字を入力できます。';
}

function setPresentation(value) {
    presentation = value;
    demo.dataset.presentation = value;
    presentationPicker.value = value;
    androidRoot.hidden = value !== 'android';
    vibrationSetting.closest('label').hidden = value !== 'android' || typeof navigator.vibrate !== 'function';
    editor.inputMode = value === 'android' ? 'none' : 'text';
    editorLabel.textContent = value === 'android' ? '入力欄' : 'ローマ字入力';
    if (loader.dataset.state === 'ready') updateReadyMessage();
    render();
}

function formatBytes(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 ** 2) return `${byteFormat.format(bytes / 1024)} KiB`;
    return `${byteFormat.format(bytes / 1024 ** 2)} MiB`;
}

function renderLoadProgress({ asset, phase, received, total, cached }) {
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
        row.dataset.size = received ? formatBytes(received) : '';
        const stage = cached
            ? 'キャッシュから復元'
            : { engine: '準備中', dictionary: '展開中', model: '取得完了' }[asset];
        detail.textContent = row.dataset.size ? `${stage} · ${row.dataset.size}` : stage;
    } else if (phase === 'initializing') {
        bar.removeAttribute('value');
        detail.textContent = 'モデルを初期化中';
        status.textContent = 'ファイルの準備が完了し、モデルを初期化しています。';
    } else if (phase === 'warmup') {
        detail.textContent = '試し変換中';
        status.textContent = '試し変換を行っています。完了すると入力できます。';
    } else if (phase === 'done') {
        bar.value = 1;
        detail.textContent = row.dataset.size ? `完了 · ${row.dataset.size}` : '完了';
    }
}

function renderModelMeta(meta) {
    if (!meta) return;
    if (meta.name) {
        if (progressModelLabel) progressModelLabel.textContent = meta.name;
        if (telemetryModel) telemetryModel.textContent = meta.name;
        if (heroModelBadge) heroModelBadge.textContent = `${meta.name} 搭載 · オープンソース日本語 IME`;
    }
    if (telemetryCache) {
        if (meta.cached) {
            telemetryCache.textContent = 'キャッシュ起動';
            telemetryCache.classList.add('is-fast');
            telemetryCache.hidden = false;
        } else if (meta.crossOriginIsolated) {
            telemetryCache.textContent = 'COOP/COEP 有効';
            telemetryCache.classList.add('is-fast');
            telemetryCache.hidden = false;
        }
    }
}

function renderLatency(elapsedMs) {
    if (!telemetryLatency || !Number.isFinite(elapsedMs)) return;
    telemetryLatency.textContent = `変換 ${elapsedMs} ms`;
    telemetryLatency.classList.toggle('is-fast', elapsedMs <= 60);
    telemetryLatency.hidden = false;
}

function loadFailed(message) {
    loader.dataset.state = 'error';
    status.textContent = message;
    retryModel.hidden = false;
}

function send(key, value) {
    if (editor.disabled) return;
    const hadOffer = Boolean(assistOffer);
    assistOffer = null;
    if (hadOffer || key === 'blur') render();
    const edit = { revision: ++revision, key, value, session };
    const previous = pendingEdits.at(-1);
    if (previous?.key === key && isBatchableAppend(edit)
        && (previous.batch || isBatchableAppend(previous))
        && previous.value.length < MAX_QUEUED_APPEND) {
        previous.value += value;
        previous.revision = edit.revision;
        previous.batch = true;
    } else {
        pendingEdits.push(edit);
    }
    sendNextEdit();
}

function isBatchableAppend({ key, value }) {
    return key === 'append' && /^[a-z]$/u.test(value)
        || key === 'appendKana' && /^[ぁ-ゖ]$/u.test(value);
}

function sendNextEdit() {
    if (inFlightRevision !== null || !pendingEdits.length) return;
    const edit = pendingEdits.shift();
    inFlightRevision = edit.revision;
    worker.postMessage(edit);
}

function positionReadingWindow() {
    if (readingFrame.hidden) return;
    const anchor = caret.getBoundingClientRect();
    const margin = 8;
    const width = readingFrame.offsetWidth;
    const height = readingFrame.offsetHeight;
    const above = anchor.top + Math.max(0, Math.min(18, anchor.height / 2) - 4) - height - 2;
    const top = above >= margin ? above : anchor.bottom + 2;
    readingFrame.style.left = `${Math.max(margin, Math.min(anchor.left - 20, innerWidth - width - margin))}px`;
    readingFrame.style.top = `${Math.max(margin, Math.min(top, innerHeight - height - margin))}px`;
}

function positionCandidates() {
    if (frame.hidden) return;
    const anchor = caret.getBoundingClientRect();
    const margin = 8;
    const width = frame.offsetWidth;
    const overlay = readingFrame.hidden ? null : readingFrame.getBoundingClientRect();
    const overlayAbove = overlay && overlay.top < anchor.top;
    const above = (overlayAbove ? overlay.top : anchor.top) - 4;
    const below = (overlay && !overlayAbove ? Math.max(anchor.bottom, overlay.bottom) : anchor.bottom) + 4;
    const roomBelow = window.innerHeight - margin - below;
    const roomAbove = above - margin;
    const placeAbove = roomBelow < candidateHeight && roomAbove > roomBelow;
    const height = Math.max(1, Math.min(candidateHeight, placeAbove ? roomAbove : roomBelow));
    const top = placeAbove ? above - height : below;
    frame.style.height = `${height}px`;
    frame.style.left = `${Math.max(margin, Math.min(anchor.left, window.innerWidth - width - margin))}px`;
    frame.style.top = `${Math.max(margin, Math.min(top, window.innerHeight - height - margin))}px`;
}

function positionWindows() {
    positionReadingWindow();
    positionCandidates();
}

function candidates() {
    frame.hidden = presentation !== 'windows' || !frameReady || !state?.candidates.length || !editing
        || (settings.showCandidatesAfterSpace && selection === null);
    if (presentation !== 'windows') return;
    if (frameReady) {
        const rows = state?.candidates ?? [];
        frame.contentWindow.zeroconRows = {
            revision: renderedRevision, ids: rows.map(row => row.id),
            forget: rows.map(row => learning?.forget && row.learningAllowed === true),
        };
        frame.contentWindow.updateCandidates(rows.map(row => row.text + row.suffix + (row.answer ? ' 〔補正〕' : '')), selection ?? 0);
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
    display.dataset.empty = String(!confirmed.hasChildNodes() && !state?.reading);
    if (presentation === 'android') renderAndroidKeyboard(state, selection, assistOffer, editor.disabled);
    renderReadingWindow();
    candidates();
}

function renderReadingWindow() {
    const update = readingFrame.contentWindow?.updateReading;
    if (typeof update !== 'function') return;
    let value = '';
    if (presentation === 'windows' && editing && state?.reading) {
        const applied = state.appliedCorrection;
        if (assistOffer) {
            value = JSON.stringify({ reading: state.reading, assist: { ...assistOffer, undoAvailable: Boolean(applied) } });
        } else if (applied) {
            value = JSON.stringify({ reading: applied.reading, assist: { status: 'applied', source: applied.source, replacement: applied.text } });
        } else if (settings.showReading) value = state.reading;
    }
    if (value === readingValue) {
        positionReadingWindow();
        return;
    }
    readingValue = value;
    if (!value) readingFrame.hidden = true;
    update(value, ++readingRequest);
}

function attachCandidateWindow() {
    frameReady = typeof frame.contentWindow?.updateCandidates === 'function';
    if (frameReady) candidates();
}

frame.addEventListener('load', attachCandidateWindow);
attachCandidateWindow();
readingFrame.addEventListener('load', () => {
    readingValue = '';
    renderReadingWindow();
});

window.addEventListener('message', event => {
    if (event.origin !== location.origin || typeof event.data !== 'string') return;
    const fromReading = event.source === readingFrame.contentWindow;
    if (!fromReading && event.source !== frame.contentWindow) return;
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    if (fromReading) {
        if (!readingValue || message.requestId !== readingRequest) return;
        if (message.type === 'ruby-resize' && Number.isFinite(message.width) && Number.isFinite(message.height)) {
            readingFrame.style.width = `${Math.min(innerWidth - 16, message.width)}px`;
            readingFrame.style.height = `${Math.min(innerHeight - 16, message.height)}px`;
            readingFrame.hidden = false;
            positionWindows();
        } else if (message.type === 'ruby-assist-click' && editing && renderedRevision === revision) {
            if (assistOffer) send('tab');
            else if (state?.appliedCorrection) send('cancel');
        }
    } else if (message.type === 'candidate-ready') {
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
    if (data.revision === inFlightRevision && ['state', 'settings', 'error'].includes(data.type)) {
        inFlightRevision = null;
        sendNextEdit();
    }
    // A commit can arrive while another edit is queued. Its text still belongs
    // to this session even when the composition snapshot is already obsolete.
    if (data.session === session && typeof data.committed === 'string') committedUpdate = data.committed;
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
        renderModelMeta(data.modelMeta);
        settings = readSettings(localStorage, { ...data.settings, learning: learning?.mode ?? 'disabled', vibration: androidVibrationDefault });
        correctionSetting.checked = settings.correction;
        predictionSetting.checked = settings.prediction;
        vibrationSetting.checked = settings.vibration;
        vibrationSetting.disabled = false;
        learningSetting.value = settings.learning;
        for (const control of [correctionSetting, predictionSetting, learningSetting, clearLearning]) control.disabled = false;
        worker.postMessage({ key: 'settings', value: settings });
        updateReadyMessage();
        render();
    } else if (data.type === 'failed') {
        loadFailed(`読み込みに失敗しました。${data.message}`);
    } else if (data.type === 'error' && data.revision === revision) {
        status.textContent = `変換に失敗しました。${data.message}`;
    } else if (data.type === 'state' && data.session === session) {
        // Show acknowledged edits while typing continues. Candidate clicks
        // still require the displayed revision to match the latest input.
        ({ state, selection } = data);
        renderedRevision = data.revision;
        renderLatency(data.elapsedMs);
        applyCommittedUpdate();
        render();
    } else if (data.type === 'settings') {
        learning = data.learning;
        if (data.session === session && (data.revision === undefined || data.revision === revision)) {
            ({ state, selection } = data);
            renderedRevision = revision;
            applyCommittedUpdate();
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
        assistOffer = line;
        render();
    } else if (data.type === 'learning') {
        if (data.result === 'conflict') learningStatus.textContent = '別のタブで学習が更新されました。保存するにはこのページを再読み込みしてください。';
        else if (data.result === 'memoryOnly') learningStatus.textContent = '学習を保存できませんでした。このタブを閉じると、今回の変更は失われます。';
        else if (data.action === 'clear') learningStatus.textContent = 'このサイトの学習を消去しました。';
        else if (data.action === 'forget') learningStatus.textContent = '選んだ候補の学習を消去しました。';
    }
});

function applyCommittedUpdate() {
    if (committedUpdate === undefined) return;
    confirmed.textContent = committedUpdate;
    committedUpdate = undefined;
}
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
    else if (event.key === 'Tab' && !event.shiftKey && (assistOffer || state?.appliedCorrection)) send('tab');
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
editor.addEventListener('blur', event => {
    editing = false;
    if (event.relatedTarget !== presentationPicker && !androidRoot.contains(event.relatedTarget)) send('blur');
    render();
});
window.addEventListener('resize', () => {
    if (!readingFrame.hidden) readingFrame.contentWindow.measureRuby();
    positionWindows();
});
window.addEventListener('scroll', positionWindows, { capture: true, passive: true });
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
vibrationSetting.addEventListener('change', () => {
    settings.vibration = vibrationSetting.checked;
    writeSettings(localStorage, settings);
});
androidRoot.querySelector('.android-settings').addEventListener('click', () => {
    const panel = document.querySelector('#demo-settings');
    panel.open = true;
    panel.querySelector('summary').focus();
});
presentationPicker.addEventListener('change', () => setPresentation(presentationPicker.value));
setPresentation(defaultPresentation());
