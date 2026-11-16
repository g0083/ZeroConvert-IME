import { ConsoleStdout, Directory, File, OpenFile, PreopenDirectory, WASI } from './wasi/dist/index.js';
import { stagedSettings } from './settings-store.mjs';
import { trackAsset } from './load-progress.mjs';

const encoder = new TextEncoder();
const decoder = new TextDecoder();
const modelName = 'zerocon-nano-v4.1-iq4nl-emb8.gguf';
const reverseName = 'reverse-v2.bin';

async function dictionaryFiles(response) {
    if (!response.ok || !response.body) throw new Error(`辞書を取得できませんでした（HTTP ${response.status}）。`);
    const stream = response.body.pipeThrough(new DecompressionStream('gzip'));
    const bytes = new Uint8Array(await new Response(stream).arrayBuffer());
    const manifestEnd = 4 + new DataView(bytes.buffer).getUint32(0, true);
    if (manifestEnd > bytes.length) throw new Error('辞書の目録が壊れています。');
    const manifest = JSON.parse(decoder.decode(bytes.subarray(4, manifestEnd)));
    const files = new Map();
    let offset = manifestEnd;
    for (const [path, length] of manifest) {
        const parts = path.split('/');
        if (parts.some(part => !part || part === '.' || part === '..')
            || !Number.isSafeInteger(length) || length < 0 || length > bytes.length - offset) {
            throw new Error('辞書ファイルが壊れています。');
        }
        const name = parts.pop();
        let target = files;
        for (const part of parts) {
            if (!target.has(part)) target.set(part, new Map());
            target = target.get(part);
        }
        if (target.has(name)) throw new Error('辞書に同名の項目があります。');
        target.set(name, new File(bytes.subarray(offset, offset + length)));
        offset += length;
    }
    if (offset !== bytes.length) throw new Error('辞書の長さが一致しません。');
    return new Directory(toEntries(files));
}

function toEntries(tree) {
    return new Map([...tree].map(([name, value]) => [name,
        value instanceof Map ? new Directory(toEntries(value)) : value]));
}

export async function loadDictionaryEngine(base = import.meta.url, request = fetch) {
    const [module, dictionary] = await Promise.all([
        WebAssembly.compileStreaming(request(new URL('./zerocon_dictionary.wasm', base))),
        request(new URL('./dictionary.gz', base)).then(dictionaryFiles),
    ]);
    const wasi = new WASI(['/runtime/zerocon_dictionary.wasm'], [], [
        new OpenFile(new File([])),
        ConsoleStdout.lineBuffered(() => {}),
        ConsoleStdout.lineBuffered(() => {}),
        new PreopenDirectory('/runtime', new Map([['Dictionary', dictionary]])),
    ], { debug: false });
    const instance = await WebAssembly.instantiate(module, { wasi_snapshot_preview1: wasi.wasiImport });
    wasi.initialize(instance);
    return requestJSON(instance.exports, instance.exports.zerocon_dictionary_convert);
}

function requestJSON(api, call, release = api.free) {
    return payload => {
        const input = encoder.encode(`${payload}\0`);
        const address = api.malloc(input.length);
        if (!address) throw new Error('入力用メモリを確保できませんでした。');
        try {
            new Uint8Array(api.memory.buffer).set(input, address);
            const result = call(address);
            if (!result) throw new Error('変換エンジンが入力を処理できませんでした。');
            try {
                const memory = new Uint8Array(api.memory.buffer);
                const end = memory.indexOf(0, result);
                if (end === -1) throw new Error('変換結果を読み取れませんでした。');
                return JSON.parse(decoder.decode(memory.subarray(result, end)));
            } finally {
                release(result);
            }
        } finally {
            api.free(address);
        }
    };
}

async function readAsset(response) {
    if (!response.ok) throw new Error(`モデル用のファイルを取得できませんでした（HTTP ${response.status}）。`);
    return new Uint8Array(await response.arrayBuffer());
}

export async function loadModelEngine(base = import.meta.url, request = fetch, learningFiles = [], report) {
    const asset = name => request(new URL(name, base));
    const tracked = (name, file) => trackAsset(name, () => asset(file), report);
    const [module, dictionary, model, settings] = await Promise.all([
        WebAssembly.compileStreaming(tracked('engine', './zerocon_model.wasm'))
            .then(module => { report?.({ asset: 'engine', phase: 'done' }); return module; }),
        tracked('dictionary', './dictionary.gz').then(dictionaryFiles)
            .then(dictionary => { report?.({ asset: 'dictionary', phase: 'done' }); return dictionary; }),
        tracked('model', `./${modelName}`).then(readAsset),
        asset('./ime-settings.json').then(readAsset),
    ]);
    report?.({ asset: 'model', phase: 'initializing' });
    const staged = JSON.parse(decoder.decode(settings));
    const { autoCommit, ...defaults } = stagedSettings(staged);
    // Early previews used "none"; the shared Swift settings use "disabled".
    const settingsFile = new File(staged.learning?.mode === 'none'
        ? encoder.encode(JSON.stringify({ ...staged, learning: { ...staged.learning, mode: defaults.learning } }))
        : settings);
    const app = new Directory(new Map([
        ['settings.json', settingsFile],
        ['LearningMemory', new Directory(new Map(learningFiles.map(([name, bytes]) => [name, new File(bytes)])))],
    ]));
    const runtime = new Map([
        ['Dictionary', dictionary],
        ['models', new Directory(new Map([[modelName, new File(model)]]))],
        ['Azookey', app],
    ]);
    const wasi = new WASI(['/runtime/zerocon_model.wasm'], ['APPDATA=/runtime'], [
        new OpenFile(new File([])),
        ConsoleStdout.lineBuffered(() => {}),
        ConsoleStdout.lineBuffered(() => {}),
        new PreopenDirectory('/runtime', runtime),
    ], { debug: false });
    const instance = await WebAssembly.instantiate(module, { wasi_snapshot_preview1: wasi.wasiImport });
    wasi.initialize(instance);
    const call = requestJSON(instance.exports, instance.exports.zerocon_model_request);
    const send = (key, fields = {}) => {
        if (key !== 'clearLearning') return call(JSON.stringify({ key, ...fields }));
        // Swift resets its caches independently of the directory operation.
        // Replace the WASI mount here: Foundation's recursive removal can
        // fail against the shim even when nothing remains on the mount.
        app.contents.delete('LearningMemory');
        const state = call(JSON.stringify({ key }));
        const empty = new Directory(new Map());
        empty.parent = app;
        app.contents.set('LearningMemory', empty);
        return { ...state, learningChanged: true };
    };
    send.autoCommit = autoCommit;
    send.stagedSettings = staged;
    send.settings = defaults;
    let mode = send.settings.learning;
    let general = staged.general;
    send.applySettings = options => {
        mode = options.learning ?? mode;
        general = { ...general,
            experimental_typo_correction: options.correction ?? general.experimental_typo_correction,
            experimental_ai_assist: options.prediction ?? general.experimental_ai_assist };
        settingsFile.data = encoder.encode(JSON.stringify({ ...staged, general, learning: { ...staged.learning, mode } }));
        return send('reloadSettings');
    };
    send.learningFiles = () => [...(app.contents.get('LearningMemory')?.contents ?? [])]
        .filter(([, file]) => file instanceof File)
        .map(([name, file]) => [name, file.data.slice()]);
    send('initialize');
    report?.({ asset: 'model', phase: 'warmup' });
    const warmup = send('append', { value: 'nihongo' });
    if (warmup.modelStatus !== `load file:///runtime/models/${modelName}`) {
        throw new Error(`ZeroCon Model を読み込めませんでした。${warmup.modelStatus}`);
    }
    send('clear');
    report?.({ asset: 'model', phase: 'done' });
    return send;
}

export async function loadLivePolicy(base = import.meta.url, request = fetch) {
    const module = await WebAssembly.compileStreaming(request(new URL('./zerocon_web_live_policy.wasm', base)));
    const wasi = new WASI([], [], [], { debug: false });
    const instance = await WebAssembly.instantiate(module, {
        wasi_snapshot_preview1: wasi.wasiImport,
        zerocon_host: { complete: () => 0 },
    });
    wasi.initialize(instance);
    const api = instance.exports;
    const call = requestJSON(api, api.zerocon_live_observe, api.zerocon_live_free);
    const select = requestJSON(api, api.zerocon_session_selection, api.zerocon_live_free);
    const decide = requestJSON(api, api.zerocon_session_decision, api.zerocon_live_free);
    const assist = requestJSON(api, api.zerocon_session_assist, api.zerocon_live_free);
    const learning = requestJSON(api, api.zerocon_session_learning, api.zerocon_live_free);
    return {
        observe: observation => call(JSON.stringify(observation)),
        select: request => select(JSON.stringify(request)),
        decide: request => decide(JSON.stringify(request)),
        assist: request => assist(JSON.stringify(request)),
        learning: request => learning(JSON.stringify(request)),
        reset: api.zerocon_live_reset,
    };
}

function put(api, bytes) {
    const address = api.malloc(bytes.length);
    if (!address) throw new Error('推論用メモリを確保できませんでした。');
    new Uint8Array(api.memory.buffer).set(bytes, address);
    return address;
}

function get(api, address) {
    const bytes = new Uint8Array(api.memory.buffer);
    const end = bytes.indexOf(0, address);
    if (!address || end === -1) throw new Error('推論結果を読み取れませんでした。');
    return decoder.decode(bytes.subarray(address, end));
}

// Construct this only inside the assistant worker. The key-handling worker
// can continue processing edits while this instance runs synchronous Wasm.
export async function loadAssistEngine(base = import.meta.url, request = fetch, completionBudgetScale = 1) {
    const asset = name => request(new URL(name, base));
    const [policy, completion, model, reverse] = await Promise.all([
        WebAssembly.compileStreaming(asset('./zerocon_web_live_policy.wasm')),
        WebAssembly.compileStreaming(asset('./zerocon_completion.wasm')),
        asset(`./${modelName}`).then(readAsset),
        asset(`./${reverseName}.gz`).then(async response => {
            if (!response.ok || !response.body) throw new Error('キーボード補正用の辞書を読み込めませんでした。');
            const bytes = new Uint8Array(await new Response(response.body.pipeThrough(new DecompressionStream('gzip'))).arrayBuffer());
            if (bytes.length < 16 || bytes.length > 64 * 1024 * 1024 || decoder.decode(bytes.subarray(0, 4)) !== 'AZR2') {
                throw new Error('キーボード補正用の辞書が壊れています。');
            }
            return bytes;
        }),
    ]);
    const io = new WASI(['/runtime/zerocon_completion.wasm'], [], [
        new OpenFile(new File([])), ConsoleStdout.lineBuffered(() => {}), ConsoleStdout.lineBuffered(() => {}),
        new PreopenDirectory('/runtime', new Map([
            [modelName, new File(model, { readonly: true })], [reverseName, new File(reverse, { readonly: true })],
        ])),
    ], { debug: false });
    const worker = await WebAssembly.instantiate(completion, { wasi_snapshot_preview1: io.wasiImport });
    io.initialize(worker);
    const modelApi = worker.exports;
    const path = put(modelApi, encoder.encode(`/runtime/${modelName}\0`));
    try {
        if (!modelApi.zerocon_completion_init(path)) throw new Error('補正と先読みのモデルを読み込めませんでした。');
    } finally {
        modelApi.free(path);
    }

    const wasi = new WASI([], [], [], { debug: false });
    let rawKeys = '';
    function complete(address, timeout) {
        const api = engine.exports;
        try {
            const serialized = get(api, address);
            const payload = JSON.parse(serialized);
            if (payload.keyboard_repair === '1') {
                // The composing host has the romaji ledger. Use it when the
                // Windows fallback only supplied the observed kana.
                const withRaw = serialized.replace('"raw":""', `"raw":"${rawKeys}"`);
                const input = put(modelApi, encoder.encode(`${withRaw}\0`));
                try {
                    const result = modelApi.zerocon_keyboard_repair(input, timeout);
                    return result ? put(api, encoder.encode(`${get(modelApi, result)}\0`)) : 0;
                } finally {
                    modelApi.free(input);
                }
            }
            const { prompt, n_predict: limit, stop } = payload;
            if (typeof prompt !== 'string' || prompt.includes('\0') || !Number.isInteger(limit)
                || !Array.isArray(stop) || stop.some(s => typeof s !== 'string' || s.includes('\0'))) return 0;
            const promptAddress = put(modelApi, encoder.encode(`${prompt}\0`));
            let stopsAddress = 0;
            try {
                const markers = encoder.encode(stop.map(s => `${s}\0`).join(''));
                stopsAddress = put(modelApi, markers.length ? markers : new Uint8Array(1));
                const budget = Math.min(5000, Math.round(timeout * completionBudgetScale));
                const result = modelApi.zerocon_complete(promptAddress, limit, budget, stopsAddress, markers.length);
                if (!result) return 0;
                const reason = ['error', 'eos', 'stop', 'limit'][modelApi.zerocon_completion_stop()];
                const response = { content: get(modelApi, result), stop_type: reason,
                    stopped_limit: reason === 'limit', stopped_eos: reason === 'eos' };
                return put(api, encoder.encode(`${JSON.stringify(response)}\0`));
            } finally {
                modelApi.free(stopsAddress);
                modelApi.free(promptAddress);
            }
        } catch {
            // Never unwind a Rust completion request through a host exception.
            return 0;
        }
    }
    const engine = await WebAssembly.instantiate(policy, {
        wasi_snapshot_preview1: wasi.wasiImport,
        zerocon_host: { complete },
    });
    wasi.initialize(engine);
    const api = engine.exports;
    const call = requestJSON(api, api.zerocon_web_assist, api.zerocon_live_free);
    const paths = call(JSON.stringify({ op: 'configurePaths', settings: '/runtime/settings.json', reverse: `/runtime/${reverseName}` }));
    if (paths?.error) throw new Error(paths.error);
    return payload => {
        rawKeys = /^[a-z]{4,64}$/u.test(payload.raw ?? '') ? payload.raw : '';
        try {
            return call(JSON.stringify(payload));
        } finally {
            rawKeys = '';
        }
    };
}
