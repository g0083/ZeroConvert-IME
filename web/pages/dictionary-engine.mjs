import { ConsoleStdout, Directory, File, OpenFile, PreopenDirectory, WASI } from './wasi/dist/index.js';

const encoder = new TextEncoder();
const decoder = new TextDecoder();

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

export async function loadModelEngine(base = import.meta.url, request = fetch) {
    const modelName = 'zerocon-nano-v4.1-iq4nl-emb8.gguf';
    const asset = name => request(new URL(name, base));
    const [module, dictionary, model, settings] = await Promise.all([
        WebAssembly.compileStreaming(asset('./zerocon_model.wasm')),
        asset('./dictionary.gz').then(dictionaryFiles),
        asset(`./${modelName}`).then(readAsset),
        asset('./ime-settings.json').then(readAsset),
    ]);
    const runtime = new Map([
        ['Dictionary', dictionary],
        ['models', new Directory(new Map([[modelName, new File(model)]]))],
        ['Azookey', new Directory(new Map([['settings.json', new File(settings)]]))],
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
    const send = (key, fields = {}) => call(JSON.stringify({ key, ...fields }));
    send.autoCommit = JSON.parse(decoder.decode(settings)).general.auto_commit_live_conversion;
    send('initialize');
    const warmup = send('append', { value: 'nihongo' });
    if (warmup.modelStatus !== `load file:///runtime/models/${modelName}`) {
        throw new Error(`ZeroCon Model を読み込めませんでした。${warmup.modelStatus}`);
    }
    send('clear');
    return send;
}

export async function loadLivePolicy(base = import.meta.url, request = fetch) {
    const module = await WebAssembly.compileStreaming(request(new URL('./zerocon_web_live_policy.wasm', base)));
    const wasi = new WASI([], [], [], { debug: false });
    const instance = await WebAssembly.instantiate(module, { wasi_snapshot_preview1: wasi.wasiImport });
    wasi.initialize(instance);
    const api = instance.exports;
    const call = requestJSON(api, api.zerocon_live_observe, api.zerocon_live_free);
    return { observe: observation => call(JSON.stringify(observation)), reset: api.zerocon_live_reset };
}
