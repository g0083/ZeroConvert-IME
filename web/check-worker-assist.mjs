// Drive the actual Worker message handler with controlled completion order.
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { createAssistClient } from './pages/model-assist-client.mjs';

const messages = [];
const messagesByKey = [];
globalThis.postMessage = message => messages.push(message);
globalThis.self = { addEventListener: (_, handler) => messagesByKey.push(handler) };
let reading = '';
let cursor = 0;
const normal = [
    { id: 'a', text: '明日の会議', suffix: '', inputCount: 7 },
    { id: 'b', text: 'アシタノカイギ', suffix: '', inputCount: 7 },
];
function request(key, fields = {}) {
    if (key === 'append') reading += fields.value;
    if (key === 'backspace') reading = reading.slice(0, -1);
    if (key === 'clear') reading = '';
    cursor = key === 'move' ? cursor + fields.offset : reading.length;
    return { raw: reading, reading, cursor, candidates: reading ? normal : [] };
}
const pending = [];
request.assist = input => new Promise((resolve, reject) => pending.push({ ...input, resolve, reject }));

const correctionId = 999901;
const predictionId = 999902;
const live = {
    assist({ op, correctionEnabled, predictionEnabled, choices, predictionDone, correctionDone, elapsedMs, automatic }) {
        if (op === 'ids') return { typoCorrection: correctionId, predictive: predictionId };
        if (op === 'plan') return { correction: correctionEnabled, prediction: predictionEnabled };
        if (op === 'ready') return { waitMs: 20, ready: automatic || (predictionDone && (correctionDone || elapsedMs >= 20)) };
        if (op === 'choose') return { selected: choices.some(([, id]) => id === correctionId) ? correctionId : predictionId };
        throw new Error(op);
    },
    decide({ action }) {
        return action === 'accept' ? { accept: 'whole' } : { delete: 'backward' };
    },
    learning: () => ({ read: false, record: false }),
    reset() {},
};
globalThis.__fixture = { request, live };
const temp = await mkdtemp(join(tmpdir(), 'zerocon-worker-assist-'));
try {
    const source = new URL('./pages/', import.meta.url);
    for (const file of ['model-worker.mjs', 'assist-session.mjs', 'learning-store.mjs', 'model-assist-client.mjs']) {
        await writeFile(join(temp, file), await readFile(new URL(file, source)));
    }
    await writeFile(join(temp, 'dictionary-engine.mjs'),
        'export const loadModelEngine = async () => globalThis.__fixture.request;\n'
        + 'export const loadLivePolicy = async () => globalThis.__fixture.live;\n');
    await import(pathToFileURL(join(temp, 'model-worker.mjs')));
    await delay(0);
    let revision = 0;
    const assist = (expected = revision) => messages.filter(m => m.type === 'assist' && m.revision === expected);
    async function send(key, value, session = 1) {
        await messagesByKey[0]({ data: { key, value, revision: ++revision, session } });
        const answer = messages.find(m => m.revision === revision);
        assert.notEqual(answer?.type, 'error', answer?.message);
        return answer;
    }
    async function start(value, session) {
        const state = await send('append', value, session);
        assert.deepEqual(state.state.candidates, normal);
        await delay(140);
        const jobs = pending.splice(0);
        assert.deepEqual(jobs.map(job => job.op), ['correction', 'prediction']);
        return jobs;
    }
    const correction = { text: 'あした会議', tokenReading: 'あしたのかいぎ', autoApply: false };
    const prediction = { text: '明日の会議があります', continuation: 'があります' };

    // Consecutive edits only submit the last reading after the idle window.
    await send('append', 'あし');
    await send('append', 'た');
    assert.equal(pending.length, 0);
    await delay(140);
    assert.deepEqual(pending.map(job => job.input), ['あした', 'あした']);
    for (const job of pending.splice(0)) job.resolve(null);
    await send('cancel');

    // Showing a prediction must not prevent a later, higher-priority correction.
    let [typo, next] = await start('あしたのかいぎ');
    next.resolve(prediction);
    await delay(30);
    assert.deepEqual(assist().map(m => m.answer.id), [predictionId]);
    assert.deepEqual(assist().at(-1).state.candidates, normal);
    typo.resolve(correction);
    await delay(0);
    assert.deepEqual(assist().map(m => m.answer.id), [predictionId, correctionId]);
    assert.deepEqual(assist().at(-1).state.candidates.map(row => row.id), ['a', String(correctionId), 'b']);

    // An assist identical to any normal row never replaces either row.
    [typo, next] = await start('あした', 2);
    typo.resolve({ text: normal[1].text });
    next.resolve(prediction);
    await delay(0);
    assert.deepEqual(assist().map(m => m.answer.id), [predictionId]);

    // Each intervening event retires the pending result, even if it would be
    // marked automatic. No delayed answer can change a committed composition.
    for (const key of ['backspace', 'left', 'cancel', 'enter', 'blur', 'settings']) {
        const field = ++revision + 1;
        [typo, next] = await start('あした', field);
        const previous = revision;
        const edited = await send(key, key === 'settings' ? { correction: true, prediction: true } : null, field);
        if (key === 'enter') assert.equal(edited.committed, normal[0].text);
        typo.resolve({ ...correction, autoApply: true });
        next.reject(new Error('timeout'));
        await delay(0);
        assert.deepEqual(assist(previous), [], key);
        // Backspace can start a fresh request, unrelated to the retired one.
        for (const job of pending.splice(0)) job.resolve(null);
    }
    await send('blur');
    console.log('Worker assist waits for idle input, protects normal rows, and retires stale edits.');
} finally {
    await rm(temp, { recursive: true });
}

// The first edit can become stale while model initialization is still in progress.
const requests = [];
let receive;
globalThis.Worker = class {
    postMessage(message) { requests.push(message); }
    addEventListener(type, listener) { if (type === 'message') receive = data => listener({ data }); }
};
const client = createAssistClient(new ArrayBuffer(1));
const stale = client({ revision: 1, input: 'あした' });
const latest = client({ revision: 2, input: 'あしたの' });
assert.equal(await stale, null);
assert.equal(requests.length, 1);
assert.equal(requests[0].type, 'initialize');
receive({ loadState: 'ready' });
assert.equal(requests.length, 2);
assert.equal(requests[1].input.input, 'あしたの');
receive({ id: requests[1].id, answer: '明日の' });
assert.equal(await latest, '明日の');
