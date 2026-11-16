import { loadLivePolicy, loadModelEngine } from './dictionary-engine.mjs';
import { assistIds, mergeAssist, planAssist } from './assist-session.mjs';
import { openLearningStore } from './learning-store.mjs';
import { createAssistClient } from './model-assist-client.mjs';

let store = null;
let storeVersion = 0;
let storeConflict = false;
let pendingLearning = null;
let savingLearning = false;
async function loadModel() {
    let files;
    try {
        store = await openLearningStore();
        if (store) ({ version: storeVersion, files } = await store.read());
    } catch {
        store = null;
    }
    return loadModelEngine(undefined, undefined, files, progress => postMessage({ type: 'load-progress', progress }));
}
const loading = Promise.all([loadModel(), loadLivePolicy()]);
let state;
let committed = '';
let selection = null;
let session = null;
// An edit, selection, settings change or focus change invalidates a request.
let generation = 0;
let assist = null;
let settings = { correction: true, prediction: true };
let learning = null;
let assistTimer;
let assistClient;
let offered = [];
let primaryAssist = null;
let appliedCorrection = null;
let offerRevision = null;
let clauseBoundary = null;

const readingLength = () => state.readingCount ?? [...state.reading].length;
const inputLength = () => state.inputCount ?? [...state.raw].length;

function candidatesWithAssist() {
    const answer = offered.find(row => row.id === assist.typoCorrection);
    if (!answer) return state.candidates;
    const rows = state.candidates.slice();
    rows.splice(1, 0, { text: answer.text, suffix: '', id: String(answer.id), inputCount: inputLength(), answer });
    return rows;
}

function snapshot() {
    if (!offered.length && !appliedCorrection) return state;
    return { ...state, candidates: candidatesWithAssist(), appliedCorrection };
}

function invalidateAssist(keepOffer = false) {
    generation += 1;
    clearTimeout(assistTimer);
    assistClient?.invalidate(generation);
    if (!keepOffer) {
        offered = [];
        primaryAssist = null;
        offerRevision = null;
    }
}

function persistLearning(request, action) {
    if (!store || storeConflict) {
        postMessage({ type: 'learning', action, result: storeConflict ? 'conflict' : 'memoryOnly' });
        return;
    }
    pendingLearning = { request, action };
    if (!savingLearning) void flushLearning();
}

async function flushLearning() {
    savingLearning = true;
    // Each snapshot includes all changes made so far. Coalesce new commits
    // while a write is pending instead of retaining an unbounded file queue.
    while (pendingLearning && !storeConflict) {
        const { request, action } = pendingLearning;
        pendingLearning = null;
        try {
            const next = await store.save(storeVersion, request.learningFiles());
            if (next === null) storeConflict = true;
            else storeVersion = next;
            postMessage({ type: 'learning', action, result: storeConflict ? 'conflict' : 'saved' });
        } catch {
            postMessage({ type: 'learning', action, result: 'memoryOnly' });
        }
    }
    pendingLearning = null;
    savingLearning = false;
}
loading.then(([request, live]) => {
    state = request('clear');
    assist = assistIds(live);
    if (request.settings) settings = request.settings;
    learning = live.learning({ mode: settings.learning ?? 'enabled', target: false });
    postMessage({ type: 'ready', state, assist, learning, settings });
}).catch(error => postMessage({ type: 'failed', message: error.message }));

function autoCommit(request, live) {
    const top = state.candidates[0];
    if (!request.autoCommit || !top || state.cursor !== readingLength()) return;
    const prefix = live.observe({ raw: state.raw, reading: state.reading, preview: top.text, suffix: top.suffix });
    if (!prefix) return;
    state = request('shrink', { offset: prefix.rawCount });
    const next = state.candidates[0]?.text;
    const boundary = next && top.text.endsWith(next) ? top.text.length - next.length : 0;
    committed += boundary ? top.text.slice(0, boundary) : prefix.surface;
    state = request('context', { value: [...committed].slice(-256).join('') });
    live.reset();
}

function commit(request, candidate, partial) {
    const text = candidate ? candidate.text + (partial ? '' : candidate.suffix) : state.reading;
    committed += text;
    selection = null;
    // Only a committed conversion is learned, and only when the mode permits
    // writing. The reading recorded is the one the engine issued for the
    // candidate, so the store keys match the Windows entries.
    if (candidate?.learningAllowed && learning?.record) {
        const learned = request('recordLearning', { candidateId: candidate.id, offset: partial ? 3 : 1 });
        if (learned.learningChanged) persistLearning(request, 'record');
    }
    if (partial && candidate.inputCount > 0 && candidate.inputCount < inputLength()) {
        state = request('shrink', { offset: candidate.inputCount });
    } else {
        state = request('clear');
    }
    appliedCorrection = null;
    state = request('context', { value: [...committed].slice(-256).join('') });
}

// The accept boundary comes from the shared policy: a suffix-bearing reading
// commits only the converted prefix, so the browser keeps the same editable
// remainder the Windows client keeps.
function accept(request, live, candidate) {
    const decision = live.decide({
        action: 'accept',
        cursor: state.cursor,
        total: readingLength(),
        has_prefix: Boolean(candidate),
        has_suffix: Boolean(candidate?.suffix),
    }).accept;
    if (decision === 'whole') commit(request, candidate, false);
    else if (decision === 'suffix') commit(request, null, false);
    else commit(request, candidate, true);
}

function startSession(request, live) {
    selection = null;
    committed = '';
    appliedCorrection = null;
    clauseBoundary = null;
    state = request('clear');
    state = request('context', { value: '' });
    live.reset();
}

function adopt(request, live, answer, explicit = true) {
    if (!answer) return false;
    const { action } = live.assist({
        op: 'adopt', id: answer.id, explicit, automatic: answer.autoApply === true,
        applied: Boolean(appliedCorrection),
    });
    if (action === 'replaceComposition') {
        const { reading } = state;
        const top = state.candidates[0];
        const source = top ? top.text + top.suffix : reading;
        state = request('applyCorrection', { value: answer.text });
        appliedCorrection = { text: answer.text, source, reading };
        selection = null;
    } else if (action === 'commitText') {
        commit(request, { text: answer.text, suffix: '', inputCount: inputLength(), answer }, false);
    } else return false;
    offered = [];
    primaryAssist = null;
    offerRevision = null;
    return true;
}

function scheduleAssist(request, live, revision) {
    const reading = state.reading;
    const length = readingLength();
    if (selection !== null || state.cursor !== length
        || length < 2 || !/[ぁ-ん]/u.test(reading)) return;
    const top = state.candidates[0];
    const baselineReading = top?.reading ?? '';
    const plan = planAssist(live, settings, appliedCorrection, reading, baselineReading);
    if (!plan.correction && !plan.prediction) return;
    const complete = request.assist ?? (assistClient ??= createAssistClient(state => postMessage({ type: 'assist-load', state })));
    const requested = generation;
    const requestSession = session;
    const baseline = top ? top.text + top.suffix : '';
    const normalSurfaces = new Set(state.candidates.map(row => row.text + row.suffix));
    const started = performance.now();
    const input = {
        input: reading, raw: state.raw, baseline, baselineReading,
        context: [...committed].slice(-256).join(''), revision: requested,
        readLearning: false,
    };
    const answers = new Map();
    let correctionDone = !plan.correction;
    let predictionDone = !plan.prediction;
    let displayed = null;

    function show() {
        if (requested !== generation) return;
        const { ready } = live.assist({
            op: 'ready', predictionDone, correctionDone,
            automatic: answers.get(assist.typoCorrection)?.autoApply === true,
            elapsedMs: Math.floor(performance.now() - started),
        });
        if (!ready) return;
        const answer = mergeAssist(live, generation, requested, [...answers.values()], displayed, baseline);
        if (!answer) return;
        // Rows arrive after the normal list; a late correction can take the
        // inline slot without moving any ordinary candidate.
        offered = [...answers.values()].filter(row => row.text !== answer.text || row.id === answer.id);
        displayed = answer.id;
        primaryAssist = answer;
        offerRevision = revision;
        const autoApply = answer.id === assist.typoCorrection && answer.autoApply === true
            && !live.decide({ action: 'pendingRomaji', raw: state.raw, reading }).pending;
        postMessage({ type: 'assist', revision, session: requestSession, answer,
            displayedAssist: displayed, autoApply, reading, state: snapshot() });
    }

    async function run(op, id) {
        let value;
        try {
            value = await complete({ ...input, op });
        } catch {
            // Assistance failure must not interfere with ordinary conversion.
        }
        if (requested !== generation) return;
        if (op === 'correction') correctionDone = true;
        else predictionDone = true;
        if (value?.text && !normalSurfaces.has(value.text)) answers.set(id, { ...value, id });
        if (correctionDone && predictionDone) clearTimeout(assistTimer);
        show();
    }

    const { waitMs } = live.assist({ op: 'ready', predictionDone, correctionDone });
    // Yield to the worker event loop before calling the assistance backend.
    assistTimer = setTimeout(() => {
        assistTimer = setTimeout(show, waitMs);
        if (plan.correction) void run('correction', assist.typoCorrection);
        if (plan.prediction) void run('prediction', assist.predictive);
    }, 0);
}

self.addEventListener('message', async ({ data }) => {
    const { revision, key, value, session: messageSession } = data;
    try {
        const [request, live] = await loading;
        if (key === 'settings' && value) {
            invalidateAssist();
            const correction = value.correction !== false;
            const prediction = value.prediction !== false;
            const next = typeof value.learning === 'string'
                ? live.learning({ mode: value.learning, target: false }) : learning;
            if (request.applySettings && (correction !== settings.correction
                || prediction !== settings.prediction || next.mode !== learning.mode)) {
                state = request.applySettings({ correction, prediction, learning: next.mode });
                selection = null;
            }
            settings = { ...settings, correction, prediction };
            learning = next;
            postMessage({ type: 'settings', revision, settings, learning, session, state, selection, committed });
            return;
        }
        if (messageSession !== session && key === 'blur') return;
        const sameSession = messageSession === session;
        const selectedBeforeEdit = sameSession && selection !== null ? candidatesWithAssist()[selection] : null;
        const selectionKey = ['space', 'up', 'down', 'select', 'enter', 'tab', 'autoApply'].includes(key);
        invalidateAssist(sameSession && selectionKey);
        if (messageSession !== session) {
            session = messageSession;
            startSession(request, live);
        }
        if (key === 'blur') {
            selection = null;
            postMessage({ type: 'state', revision, session, state, selection, committed });
            return;
        }
        if (key === 'forgetAll' || key === 'forget') {
            const row = state.candidates.find(row => row.id === value?.id);
            const cleared = key === 'forgetAll';
            if ((cleared && learning?.clear) || (!cleared && learning?.forget && row?.learningAllowed && row.id === value.id)) {
                state = cleared ? request('clearLearning') : request('forgetLearning', { candidateId: row.id });
                selection = null;
                if (state.learningChanged) persistLearning(request, cleared ? 'clear' : 'forget');
            }
            postMessage({ type: 'state', revision, session, state, selection, committed });
            return;
        }
        if (key !== 'append' || selection !== null) live.reset();
        if (!['adjustClause', 'space', 'up', 'down'].includes(key)) clauseBoundary = null;
        if (key === 'append' || key === 'sample') {
            if (key === 'append' && selection !== null && state.reading) {
                commit(request, selectedBeforeEdit ?? state.candidates[0], false);
            }
            appliedCorrection = null;
            selection = null;
            if (key === 'sample') {
                state = request('clear');
                state = request('context', { value: '' });
                committed = '';
                live.reset();
                if (typeof value !== 'string' || !/^[a-z]{1,32}$/u.test(value)) value = 'ashitanokaigi';
            }
            for (const char of value) {
                if (inputLength() >= 256) break;
                state = request('append', { value: char });
                if (key === 'append') autoCommit(request, live);
            }
        } else if (key === 'space' || key === 'up' || key === 'down') {
            appliedCorrection = null;
            const count = candidatesWithAssist().length;
            if (count) {
                selection = live.select({
                    current: selection ?? 0,
                    candidates: count,
                    direction: key === 'up' ? 'previous' : 'next',
                });
            } else if (key === 'space' && !state.reading) {
                committed += ' ';
                state = request('context', { value: [...committed].slice(-256).join('') });
            }
        } else if (key === 'select' && value) {
            const row = candidatesWithAssist()[value.index];
            if (row?.id === value.id) {
                if (row.answer) adopt(request, live, row.answer);
                else commit(request, row, true);
            }
        } else if (key === 'tab') {
            adopt(request, live, primaryAssist);
        } else if (key === 'autoApply') {
            const match = value?.revision === offerRevision && value?.id === primaryAssist?.id;
            if (match && selection === null && settings.correction && primaryAssist?.id === assist.typoCorrection
                && !live.decide({ action: 'pendingRomaji', raw: state.raw, reading: state.reading }).pending) {
                adopt(request, live, primaryAssist, false);
            }
        } else if (key === 'enter') {
            const selected = candidatesWithAssist()[selection ?? 0];
            if (selected?.answer) adopt(request, live, selected.answer);
            else if (state.reading) accept(request, live, selected);
            else {
                committed += '\n';
                state = request('context', { value: [...committed].slice(-256).join('') });
            }
        } else if (key === 'cancel' && appliedCorrection) {
            state = request('undoCorrection');
            appliedCorrection = null;
            selection = null;
            postMessage({ type: 'state', revision, session, state, selection, committed });
            return;
        } else if (key === 'cancel' && selection !== null) {
            selection = null;
        } else if (key === 'cancel') {
            state = request('clear');
        } else if (key === 'adjustClause' && state.reading && (value === -1 || value === 1)) {
            const boundary = clauseBoundary ?? selectedBeforeEdit?.inputCount ?? state.candidates[0]?.inputCount ?? inputLength();
            state = request('adjustClause', { inputCount: boundary, offset: value, value: state.raw });
            clauseBoundary = state.adjustedInputCount ?? boundary;
            selection = null;
            appliedCorrection = null;
        } else if (key === 'backspace' || key === 'delete' || key === 'left' || key === 'right') {
            selection = null;
            appliedCorrection = null;
            if (key === 'left' || key === 'right') {
                if (state.reading) state = request('move', { offset: key === 'left' ? -1 : 1 });
            } else {
                const deletion = live.decide({
                    action: key,
                    cursor: state.cursor,
                    total: readingLength(),
                }).delete;
                if (deletion === 'backward') state = request('backspace');
                else if (deletion === 'forward') state = request('delete');
                else if (key === 'backspace' && !state.reading) {
                    committed = [...committed].slice(0, -1).join('');
                    state = request('context', { value: [...committed].slice(-256).join('') });
                }
            }
        }
        if (key === 'enter' || (key === 'select' && selection === null)) {
            offered = [];
            primaryAssist = null;
        }
        postMessage({ type: 'state', revision, session, state: snapshot(), selection, committed });
        if (['append', 'sample', 'backspace', 'delete', 'left', 'right', 'autoApply'].includes(key)
            || (appliedCorrection && ['tab', 'select'].includes(key))) {
            scheduleAssist(request, live, revision);
        }
    } catch (error) {
        postMessage({ type: 'error', revision, message: error.message });
    }
});
