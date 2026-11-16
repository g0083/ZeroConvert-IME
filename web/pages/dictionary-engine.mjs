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
    const api = instance.exports;

    return raw => {
        const input = encoder.encode(`${raw}\0`);
        const address = api.malloc(input.length);
        if (!address) throw new Error('入力用メモリを確保できませんでした。');
        try {
            new Uint8Array(api.memory.buffer).set(input, address);
            const result = api.zerocon_dictionary_convert(address);
            if (!result) throw new Error('変換エンジンが入力を処理できませんでした。');
            try {
                const memory = new Uint8Array(api.memory.buffer);
                const end = memory.indexOf(0, result);
                if (end === -1) throw new Error('変換結果を読み取れませんでした。');
                return JSON.parse(decoder.decode(memory.subarray(result, end)));
            } finally {
                api.free(result);
            }
        } finally {
            api.free(address);
        }
    };
}
