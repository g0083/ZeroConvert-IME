// Verifies the Web Worker's session behaviour against fixed engine responses:
// candidate order and selection, partial-commit boundaries, deletion, cancel,
// and session isolation when the user switches input fields.
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const workerSource = await readFile(resolve(here, 'pages/model-worker.mjs'), 'utf8');

const rows = [
    { text: '明日の会議', suffix: '', inputCount: 9, id: '1', predictionReading: '', learningAllowed: true },
    { text: '明日の懐疑', suffix: 'ぎ', inputCount: 7, id: '2', predictionReading: '', learningAllowed: true },
    { text: 'アシタノカイギ', suffix: '', inputCount: 9, id: '3', predictionReading: '' },
];
const engineState = { reading: '', raw: '', cursor: 0, candidates: [], modelStatus: 'ok' };
const learningCalls = [];
const clone = () => structuredClone(engineState);
function request(key, fields = {}) {
    switch (key) {
        case 'recordLearning':
        case 'forgetLearning':
        case 'clearLearning':
            learningCalls.push({ key, ...fields });
            return { ...clone(), learningChanged: true };
        case 'append':
            engineState.raw += fields.value;
            engineState.reading += fields.value;
            engineState.cursor = [...engineState.reading].length;
            engineState.candidates = engineState.raw ? rows : [];
            return clone();
        case 'move':
            engineState.cursor = Math.min(
                Math.max(0, engineState.cursor + fields.offset),
                [...engineState.reading].length,
            );
            return clone();
        case 'backspace':
            engineState.raw = [...engineState.raw].slice(0, -1).join('');
            engineState.reading = [...engineState.reading].slice(0, -1).join('');
            engineState.cursor = [...engineState.reading].length;
            engineState.candidates = engineState.raw ? rows : [];
            return clone();
        case 'delete':
        case 'context':
            return clone();
        case 'shrink':
            engineState.raw = [...engineState.raw].slice(fields.offset).join('');
            engineState.reading = [...engineState.reading].slice(fields.offset).join('');
            engineState.cursor = Math.max(0, engineState.cursor - fields.offset);
            engineState.candidates = engineState.raw ? rows : [];
            return clone();
        case 'clear':
            engineState.raw = '';
            engineState.reading = '';
            engineState.cursor = 0;
            engineState.candidates = [];
            return clone();
        default:
            throw new Error(`unexpected engine call ${key}`);
    }
}
request.autoCommit = false;

const live = {
    // These mirror the shared wasm results the Worker receives in the browser.
    observe() { return null; },
    select({ current, candidates, direction }) {
        if (!candidates) return null;
        if (direction === 'previous') return Math.max(0, current - 1);
        return Math.min(candidates - 1, current + 1);
    },
    decide({ action, cursor, total, has_prefix: hasPrefix, has_suffix: hasSuffix }) {
        if (action === 'accept') {
            if (!hasSuffix) return { accept: 'whole' };
            return { accept: hasPrefix ? 'prefix' : 'suffix' };
        }
        if (cursor === 0 || total === 0) return { delete: 'none' };
        if (action === 'delete' && cursor >= total) return { delete: 'none' };
        return { delete: action === 'backspace' ? 'backward' : 'forward' };
    },
    assist(request) {
        if (request.op === 'ids') return { typoCorrection: 999901, predictive: 999902, translation: 999904 };
        if (request.op === 'plan') {
            return {
                correction: Boolean(request.correctionEnabled) && !request.applied,
                prediction: Boolean(request.predictionEnabled),
            };
        }
        if (request.op === 'choose') {
            const rank = id => (id === 999904 ? 3 : id === 999901 ? 2 : id === 999902 ? 1 : 0);
            const best = request.choices.reduce((a, b) => (rank(b[1]) > rank(a[1]) || (rank(b[1]) === rank(a[1]) && b[0] > a[0]) ? b : a));
            return { selected: request.displayed ?? best[1] };
        }
        if (request.op === 'adopt') {
            if (request.id === 999901 && !request.applied && (request.explicit || request.automatic)) return { action: 'replaceComposition' };
            if ((request.id === 999902 || request.id === 999904) && request.explicit) return { action: 'commitText' };
            return { action: 'ignore' };
        }
        return {};
    },
    learning({ mode, target }) {
        const normalized = mode === 'read_only' ? 'read_only' : mode === 'disabled' ? 'disabled' : 'enabled';
        const record = normalized === 'enabled';
        return {
            mode: normalized,
            record,
            read: normalized !== 'disabled',
            forget: normalized !== 'disabled',
            clear: true,
            recordCommit: record && Boolean(target),
        };
    },
    reset() {},
};

globalThis.__zeroconStub = {
    loadModelEngine: async () => request,
    loadLivePolicy: async () => live,
};

globalThis.postMessage = message => engineMessages.push(message);
const engineMessages = [];
const listeners = [];
globalThis.self = {
    addEventListener(type, listener) { if (type === 'message') listeners.push(listener); },
    postMessage: globalThis.postMessage,
};

const directory = await mkdtemp(join(tmpdir(), 'zerocon-worker-'));
process.once('beforeExit', () => { void rm(directory, { recursive: true, force: true }); });
const stub = 'export const loadModelEngine = () => globalThis.__zeroconStub.loadModelEngine();\n'
    + 'export const loadLivePolicy = () => globalThis.__zeroconStub.loadLivePolicy();\n';
await writeFile(join(directory, 'dictionary-engine.mjs'), stub);
await writeFile(join(directory, 'assist-session.mjs'), await readFile(resolve(here, 'pages/assist-session.mjs'), 'utf8'));
await writeFile(join(directory, 'learning-store.mjs'), await readFile(resolve(here, 'pages/learning-store.mjs'), 'utf8'));
await writeFile(join(directory, 'model-assist-client.mjs'), await readFile(resolve(here, 'pages/model-assist-client.mjs'), 'utf8'));
await writeFile(join(directory, 'model-worker.mjs'), workerSource);
await import(pathToFileURL(join(directory, 'model-worker.mjs')).href);
await new Promise(resolvePending => setTimeout(resolvePending, 0));
assert.equal(engineMessages[0].type, 'ready');

let revision = 0;
async function send(key, value, session = 1) {
    const before = engineMessages.length;
    await listeners[0]({ data: { revision: ++revision, key, value, session } });
    const reply = engineMessages.slice(before).find(message => message.revision === revision);
    assert.ok(reply, `no reply for ${key}`);
    if (reply.type === 'error') throw new Error(`${key}: ${reply.message}`);
    return reply;
}

let reply = await send('append', 'ashitanokaigi');
assert.equal(reply.state.candidates.length, 3);
assert.equal(reply.selection, null);

// The first Space picks the row after the composing position, matching the
// Windows `SetSelection(Down)` that opens the list.
reply = await send('space');
assert.equal(reply.selection, 1);
reply = await send('space');
assert.equal(reply.selection, 2);
reply = await send('space');
assert.equal(reply.selection, 2, 'Space clamps at the last candidate instead of wrapping');
reply = await send('up');
assert.equal(reply.selection, 1);
reply = await send('up');
assert.equal(reply.selection, 0);

// Enter on a suffix-bearing candidate keeps the unconverted tail editable.
reply = await send('space');
assert.equal(reply.selection, 1);
const beforeCommit = engineMessages.length;
reply = await send('enter');
assert.equal(reply.selection, null);
assert.equal(reply.committed, '明日の懐疑');
assert.equal([...reply.state.raw].length, 6, 'a partial commit keeps the remaining reading');
// Learning writes only the committed conversion, keyed by the engine reading.
assert.ok(engineMessages.slice(beforeCommit).some(message => message.type === 'learning'));
assert.deepEqual(learningCalls.at(-1), { key: 'recordLearning', candidateId: '2', offset: 3 });

// Cancel clears the remainder without touching committed text, then Backspace
// removes one committed character only when the composition is empty.
reply = await send('cancel');
assert.equal(reply.state.reading, '');
assert.equal(reply.committed, '明日の懐疑');
reply = await send('backspace');
assert.equal([...reply.committed].length, [...'明日の懐疑'].length - 1);
reply = await send('backspace');
const afterFirst = [...reply.committed].length;
reply = await send('backspace');
assert.equal([...reply.committed].length, Math.max(0, afterFirst - 1));

// Cancel closes the candidate list before it clears the reading.
await send('append', 'b');
reply = await send('space');
assert.equal(reply.selection, 1);
reply = await send('cancel');
assert.equal(reply.selection, null);
assert.equal(reply.state.reading.endsWith('b'), true);
reply = await send('cancel');
assert.equal(reply.state.reading, '');

// Switching input fields starts a clean session.
await send('append', 'c', 1);
reply = await send('append', 'd', 2);
assert.equal(reply.state.reading, 'd');
assert.equal(reply.committed, '');

// `forgetAll` clears the stored learning through the shared clear capability.
const beforeForget = engineMessages.length;
reply = await send('forgetAll');
const cleared = engineMessages.slice(beforeForget).find(message => message.type === 'learning');
assert.ok(cleared, 'clearing learning reports the emptied store');
assert.equal(cleared.action, 'clear');
assert.deepEqual(learningCalls.at(-1), { key: 'clearLearning' });

// A settings message applies the shared learning capability, and a mode that
// may not write stops further learning writes.
await send('settings', { correction: true, prediction: true, learning: 'read_only' });
const readOnlyReply = engineMessages.filter(message => message.type === 'settings').at(-1);
assert.equal(readOnlyReply.learning.mode, 'read_only');
assert.equal(readOnlyReply.learning.record, false);
await send('append', 'e');
reply = await send('space');
const beforeReadOnlyCommit = engineMessages.length;
await send('enter');
assert.ok(
    !engineMessages.slice(beforeReadOnlyCommit).some(message => message.type === 'learning'),
    'read_only must not write learning on commit',
);
assert.deepEqual(learningCalls.at(-1), { key: 'clearLearning' });

process.stdout.write('Web Worker session behaviour matches the shared transitions.\n');
