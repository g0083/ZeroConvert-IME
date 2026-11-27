import { ConsoleStdout, Directory, File, OpenFile, PreopenDirectory, WASI } from './wasi/dist/index.js';
import { stagedSettings } from './settings-store.mjs';
import { trackAsset } from './load-progress.mjs';

const encoder = new TextEncoder();
const decoder = new TextDecoder();
const defaultModelName = 'zerocon-nano-v4.2b-iq4nl-emb8.gguf';
const reverseName = 'reverse-v2.bin';
const ASSET_CACHE_NAME = 'zerocon-ime-assets-v1';

async function loadAssetManifest(base, request) {
    try {
        const response = await request(new URL('./asset-manifest.json', base), { cache: 'no-cache' });
        if (!response.ok) return null;
        return await response.json();
    } catch {
        return null;
    }
}

async function openAssetCache() {
    if (typeof caches === 'undefined') return null;
    try {
        return await caches.open(ASSET_CACHE_NAME);
    } catch {
        return null;
    }
}

async function pruneAssetCache(cache, activeUrls) {
    if (!cache || !activeUrls.size) return;
    try {
        const keys = await cache.keys();
        await Promise.all(keys
            .filter(req => !activeUrls.has(req.url))
            .map(req => cache.delete(req)));
    } catch {
        // Ignore cache eviction errors.
    }
}

function versionedUrl(name, base, sha256) {
    const url = new URL(name, base);
    if (sha256) url.searchParams.set('v', sha256.slice(0, 12));
    return url;
}

function decompressResponse(response, logicalName) {
    if (!response.ok || !response.body) return response;
    const stream = response.body.pipeThrough(new DecompressionStream('gzip'));
    const contentType = logicalName.endsWith('.wasm') ? 'application/wasm' : 'application/octet-stream';
    return new Response(stream, { status: 200, headers: { 'Content-Type': contentType } });
}

async function fetchChunkedAsset(assetKey, fileName, entry, base, request, report) {
    const chunks = entry.chunks;
    const totalBytes = entry.bytes || chunks.reduce((sum, part) => sum + (part.bytes || 0), 0);
    report?.({ asset: assetKey, phase: 'downloading', received: 0, total: totalBytes || null });
    const receivedByChunk = new Array(chunks.length).fill(0);
    let lastReport = -Infinity;
    const notify = () => {
        const now = performance.now();
        if (now - lastReport < 100) return;
        lastReport = now;
        const received = receivedByChunk.reduce((sum, value) => sum + value, 0);
        report?.({ asset: assetKey, phase: 'downloading', received, total: totalBytes || null });
    };

    const buffers = await Promise.all(chunks.map(async (part, index) => {
        const response = await request(versionedUrl(`./${part.name}`, base, entry.sha256));
        if (!response.ok || !response.body) {
            throw new Error(`アセット分割ファイルを取得できませんでした（HTTP ${response.status}）。`);
        }
        const reader = response.body.getReader();
        const pieces = [];
        let length = 0;
        while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            pieces.push(value);
            length += value.byteLength;
            receivedByChunk[index] = length;
            notify();
        }
        const merged = new Uint8Array(length);
        let offset = 0;
        for (const piece of pieces) {
            merged.set(piece, offset);
            offset += piece.byteLength;
        }
        return merged;
    }));

    const fullLength = buffers.reduce((sum, part) => sum + part.byteLength, 0);
    const combined = new Uint8Array(fullLength);
    let offset = 0;
    for (const part of buffers) {
        combined.set(part, offset);
        offset += part.byteLength;
    }
    report?.({ asset: assetKey, phase: 'downloaded', received: fullLength });
    const contentType = (!entry.gzip && fileName.endsWith('.wasm')) ? 'application/wasm' : 'application/octet-stream';
    return new Response(combined, { status: 200, headers: { 'Content-Type': contentType, 'Content-Length': String(fullLength) } });
}

async function fetchManifestAsset(assetKey, fileName, manifest, base, request, cache, activeUrls, report) {
    const entry = manifest?.assets?.[fileName];
    const remoteName = entry?.file || fileName;
    const url = versionedUrl(`./${remoteName}`, base, entry?.sha256);
    if (entry?.sha256) activeUrls.add(url.href);

    const finalize = response => (entry?.gzip ? decompressResponse(response, fileName) : response);

    if (cache && entry?.sha256) {
        try {
            const cached = await cache.match(url.href);
            if (cached && cached.ok) {
                const size = entry.bytes || Number(cached.headers.get('Content-Length')) || 0;
                report?.({ asset: assetKey, phase: 'downloaded', received: size, cached: true });
                return finalize(cached);
            }
        } catch {
            // Fall through to network fetch.
        }
    }

    const response = entry?.chunks?.length
        ? await fetchChunkedAsset(assetKey, fileName, entry, base, request, report)
        : await trackAsset(assetKey, () => request(url), report);

    if (cache && entry?.sha256 && response.ok) {
        try {
            const bytes = new Uint8Array(await response.arrayBuffer());
            const headers = new Headers(response.headers);
            if (!entry.gzip && fileName.endsWith('.wasm')) headers.set('Content-Type', 'application/wasm');
            headers.delete('Content-Encoding');
            headers.set('Content-Length', String(bytes.byteLength));
            const storable = new Response(bytes, { status: 200, headers });
            void cache.put(url.href, storable.clone()).catch(() => {});
            return finalize(storable);
        } catch {
            return finalize(response);
        }
    }
    return finalize(response);
}

async function compileWasmResponse(response) {
    if (!response.ok) throw new Error(`変換エンジンを取得できませんでした（HTTP ${response.status}）。`);
    const type = response.headers.get('Content-Type') || '';
    if (typeof WebAssembly.compileStreaming === 'function' && type.includes('application/wasm')) {
        try {
            return await WebAssembly.compileStreaming(Promise.resolve(response.clone()));
        } catch {
            // Fall back to arrayBuffer compilation for synthetic or cached responses.
        }
    }
    return WebAssembly.compile(await response.arrayBuffer());
}

function desiredThreadCount() {
    const isNode = typeof process !== 'undefined' && Boolean(process.versions?.node);
    const hasSab = typeof SharedArrayBuffer !== 'undefined'
        && (isNode || globalThis.crossOriginIsolated === true);
    if (!hasSab) return 1;
    const hw = typeof navigator !== 'undefined' && Number.isFinite(navigator.hardwareConcurrency)
        ? navigator.hardwareConcurrency
        : 4;
    return Math.max(1, Math.min(4, hw));
}

async function loadLlamaMt(base, request, manifest, cache, activeUrls) {
    const { default: createZeroConLlamaMt } = await import(new URL('./zerocon_llama_mt.mjs', base).href);
    const wasmResponse = await fetchManifestAsset(
        'llama', 'zerocon_llama_mt.wasm', manifest, base, request, cache, activeUrls, null,
    );
    const wasmBinary = new Uint8Array(await wasmResponse.arrayBuffer());
    const mt = await createZeroConLlamaMt({
        wasmBinary,
        locateFile(path) {
            return new URL(`./${path}`, base).href;
        },
    });
    const threads = desiredThreadCount();
    mt._mt_set_default_threads(threads);
    if (typeof process !== 'undefined' && process.versions?.node) {
        process.once('beforeExit', () => {
            try { mt.PThread?.terminateAllThreads?.(); } catch {}
        });
    }
    return { mt, threads };
}

function createLlamaHostImports(mt, getWasiMemory) {
    const wasiU8 = () => new Uint8Array(getWasiMemory().buffer);
    const wasiI32 = () => new Int32Array(getWasiMemory().buffer);
    const wasiF32 = () => new Float32Array(getWasiMemory().buffer);

    function mtAllocCopyU8(bytes) {
        const ptr = mt._malloc(Math.max(1, bytes.byteLength));
        if (!ptr) throw new Error('Emscripten メモリを確保できませんでした。');
        if (bytes.byteLength) mt.HEAPU8.set(bytes, ptr);
        return ptr;
    }

    function mtAllocCopyI32(wasiPtr, count) {
        const byteLen = count * 4;
        const ptr = mt._malloc(Math.max(4, byteLen));
        if (!ptr) throw new Error('Emscripten メモリを確保できませんでした。');
        if (count > 0) {
            mt.HEAP32.set(wasiI32().subarray(wasiPtr >> 2, (wasiPtr >> 2) + count), ptr >> 2);
        }
        return ptr;
    }

    return {
        backend_init: () => mt._mt_llama_backend_init(),
        backend_free: () => mt._mt_llama_backend_free(),
        model_load(pathPtr, pathLen) {
            const pathBytes = wasiU8().subarray(pathPtr, pathPtr + pathLen);
            const cstr = new Uint8Array(pathLen + 1);
            cstr.set(pathBytes);
            const mtPath = mtAllocCopyU8(cstr);
            try {
                return mt._mt_llama_model_load(mtPath);
            } finally {
                mt._free(mtPath);
            }
        },
        model_free: model => mt._mt_llama_model_free(model),
        model_size: model => BigInt(mt._mt_llama_model_size(model)),
        model_get_vocab: model => mt._mt_llama_model_get_vocab(model),
        vocab_n_tokens: vocab => mt._mt_llama_vocab_n_tokens(vocab),
        vocab_bos: vocab => mt._mt_llama_vocab_bos(vocab),
        vocab_eos: vocab => mt._mt_llama_vocab_eos(vocab),
        vocab_is_eog: (vocab, token) => mt._mt_llama_vocab_is_eog(vocab, token),
        tokenize(vocab, textPtr, textLen, tokensPtr, nTokensMax, addSpecial, parseSpecial) {
            const textBytes = wasiU8().subarray(textPtr, textPtr + textLen);
            const mtText = mtAllocCopyU8(textBytes);
            const mtTokens = mt._malloc(Math.max(4, nTokensMax * 4));
            try {
                const count = mt._mt_llama_tokenize(
                    vocab, mtText, textLen, mtTokens, nTokensMax, addSpecial, parseSpecial,
                );
                if (count > 0 && tokensPtr) {
                    wasiI32().set(mt.HEAP32.subarray(mtTokens >> 2, (mtTokens >> 2) + count), tokensPtr >> 2);
                }
                return count;
            } finally {
                mt._free(mtTokens);
                mt._free(mtText);
            }
        },
        token_to_piece(vocab, token, bufPtr, length, lstrip, special) {
            const mtBuf = mt._malloc(Math.max(1, length));
            try {
                const count = mt._mt_llama_token_to_piece(vocab, token, mtBuf, length, lstrip, special);
                if (count > 0 && bufPtr) {
                    wasiU8().set(mt.HEAPU8.subarray(mtBuf, mtBuf + count), bufPtr);
                }
                return count;
            } finally {
                mt._free(mtBuf);
            }
        },
        init_from_model: (model, nCtx, nBatch, nUbatch, nThreads, nThreadsBatch, flashAttn) =>
            mt._mt_llama_init_from_model(model, nCtx, nBatch, nUbatch, nThreads, nThreadsBatch, flashAttn),
        free_ctx: ctx => mt._mt_llama_free(ctx),
        n_ctx: ctx => mt._mt_llama_n_ctx(ctx),
        n_batch: ctx => mt._mt_llama_n_batch(ctx),
        set_n_threads: (ctx, nThreads, nThreadsBatch) => mt._mt_llama_set_n_threads(ctx, nThreads, nThreadsBatch),
        memory_clear: (ctx, data) => mt._mt_llama_memory_clear(ctx, data),
        memory_seq_rm: (ctx, seqId, p0, p1) => mt._mt_llama_memory_seq_rm(ctx, seqId, p0, p1),
        memory_seq_cp: (ctx, src, dst, p0, p1) => mt._mt_llama_memory_seq_cp(ctx, src, dst, p0, p1),
        memory_seq_pos_max: (ctx, seqId) => mt._mt_llama_memory_seq_pos_max(ctx, seqId),
        decode_one(ctx, tokensPtr, nTokens) {
            const mtTokens = mtAllocCopyI32(tokensPtr, nTokens);
            try {
                return mt._mt_llama_decode_one(ctx, mtTokens, nTokens);
            } finally {
                mt._free(mtTokens);
            }
        },
        decode_flat(ctx, nTokens, tokensPtr, posPtr, nSeqPtr, seqIdsPtr, totalSeqIds, logitsPtr) {
            const mtTokens = mtAllocCopyI32(tokensPtr, nTokens);
            const mtPos = mtAllocCopyI32(posPtr, nTokens);
            const mtNSeq = mtAllocCopyI32(nSeqPtr, nTokens);
            const mtSeqIds = mtAllocCopyI32(seqIdsPtr, Math.max(1, totalSeqIds));
            const mtLogits = mtAllocCopyU8(wasiU8().subarray(logitsPtr, logitsPtr + nTokens));
            try {
                return mt._mt_llama_decode_flat(ctx, nTokens, mtTokens, mtPos, mtNSeq, mtSeqIds, mtLogits);
            } finally {
                mt._free(mtLogits);
                mt._free(mtSeqIds);
                mt._free(mtNSeq);
                mt._free(mtPos);
                mt._free(mtTokens);
            }
        },
        logits_size(ctx) {
            return mt._mt_llama_get_logits_count(ctx) * mt._mt_llama_get_vocab_size_for_ctx(ctx);
        },
        copy_logits(ctx, dstPtr, count) {
            const srcPtr = mt._mt_llama_get_logits_ptr(ctx);
            if (!srcPtr || count <= 0) return 0;
            wasiF32().set(mt.HEAPF32.subarray(srcPtr >> 2, (srcPtr >> 2) + count), dstPtr >> 2);
            return 1;
        },
        logits_ith_size(ctx) {
            return mt._mt_llama_get_vocab_size_for_ctx(ctx);
        },
        copy_logits_ith(ctx, i, dstPtr, count) {
            const srcPtr = mt._mt_llama_get_logits_ith_ptr(ctx, i);
            if (!srcPtr || count <= 0) return 0;
            wasiF32().set(mt.HEAPF32.subarray(srcPtr >> 2, (srcPtr >> 2) + count), dstPtr >> 2);
            return 1;
        },
        sampler_init_greedy: () => mt._mt_llama_sampler_init_greedy(),
        sampler_sample: (smpl, ctx, idx) => mt._mt_llama_sampler_sample(smpl, ctx, idx),
        sampler_accept: (smpl, token) => mt._mt_llama_sampler_accept(smpl, token),
        sampler_reset: smpl => mt._mt_llama_sampler_reset(smpl),
        sampler_free: smpl => mt._mt_llama_sampler_free(smpl),
    };
}

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
    const manifest = await loadAssetManifest(base, request);
    const modelName = manifest?.model?.filename || defaultModelName;
    const cache = request === fetch ? await openAssetCache() : null;
    const activeUrls = new Set();
    const asset = name => request(new URL(name, base));
    let usedCache = false;
    const wrappedReport = event => {
        if (event?.cached) usedCache = true;
        report?.(event);
    };

    const [module, dictionary, model, settings, llamaMtResult] = await Promise.all([
        fetchManifestAsset('engine', 'zerocon_model.wasm', manifest, base, request, cache, activeUrls, wrappedReport)
            .then(compileWasmResponse)
            .then(compiled => { wrappedReport({ asset: 'engine', phase: 'done' }); return compiled; }),
        fetchManifestAsset('dictionary', 'dictionary.gz', manifest, base, request, cache, activeUrls, wrappedReport)
            .then(dictionaryFiles)
            .then(loaded => { wrappedReport({ asset: 'dictionary', phase: 'done' }); return loaded; }),
        fetchManifestAsset('model', modelName, manifest, base, request, cache, activeUrls, wrappedReport)
            .then(readAsset),
        asset('./ime-settings.json').then(readAsset),
        loadLlamaMt(base, request, manifest, cache, activeUrls),
    ]);

    const { mt, threads } = llamaMtResult;

    if (cache && manifest?.assets) {
        for (const [name, entry] of Object.entries(manifest.assets)) {
            if (entry?.sha256) activeUrls.add(versionedUrl(`./${entry.file || name}`, base, entry.sha256).href);
        }
        void pruneAssetCache(cache, activeUrls);
    }

    wrappedReport({ asset: 'model', phase: 'initializing' });
    // Stage the GGUF into Emscripten's virtual FS so multi-threaded llama.cpp loads it directly.
    mt.FS.mkdirTree('/runtime/models');
    mt.FS.writeFile(`/runtime/models/${modelName}`, model);

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
    // Keep a lightweight placeholder entry in WASI FS so Swift's FileManager.fileExists checks succeed.
    const modelPlaceholder = new File(new Uint8Array(1), { readonly: true });
    const runtime = new Map([
        ['Dictionary', dictionary],
        ['models', new Directory(new Map([[modelName, modelPlaceholder]]))],
        ['Azookey', app],
    ]);
    const wasi = new WASI(['/runtime/zerocon_model.wasm'], ['APPDATA=/runtime'], [
        new OpenFile(new File([])),
        ConsoleStdout.lineBuffered(() => {}),
        ConsoleStdout.lineBuffered(() => {}),
        new PreopenDirectory('/runtime', runtime),
    ], { debug: false });

    let instance;
    const llamaImports = createLlamaHostImports(mt, () => instance.exports.memory);
    instance = await WebAssembly.instantiate(module, {
        wasi_snapshot_preview1: wasi.wasiImport,
        zerocon_llama: llamaImports,
    });
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
    send.modelMeta = {
        id: manifest?.model?.id || staged?.zenzai?.model || '',
        name: manifest?.model?.name || 'ZeroCon Model Nano v4.2b',
        filename: modelName,
        releaseTag: manifest?.model?.releaseTag || '',
        commit: manifest?.build?.commit || '',
        builtAt: manifest?.build?.builtAt || '',
        cached: usedCache,
        threads,
        crossOriginIsolated: typeof globalThis.crossOriginIsolated === 'boolean' ? globalThis.crossOriginIsolated : false,
    };
    // Preserve the conversion instance's backing bytes and transfer one copy to the assist Worker.
    send.copyModel = () => model.slice().buffer;
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
    wrappedReport({ asset: 'model', phase: 'warmup' });
    const warmup = send('append', { value: 'nihongo' });
    if (warmup.modelStatus !== `load file:///runtime/models/${modelName}`) {
        throw new Error(`ZeroCon Model を読み込めませんでした。${warmup.modelStatus}`);
    }
    send('clear');
    wrappedReport({ asset: 'model', phase: 'done' });
    return send;
}

export async function loadLivePolicy(base = import.meta.url, request = fetch) {
    const manifest = await loadAssetManifest(base, request);
    const cache = request === fetch ? await openAssetCache() : null;
    const response = await fetchManifestAsset(
        'policy', 'zerocon_web_live_policy.wasm', manifest, base, request, cache, new Set(), null,
    );
    const module = await compileWasmResponse(response);
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

function mtPut(mt, bytes) {
    const address = mt._malloc(Math.max(1, bytes.length));
    if (!address) throw new Error('推論用メモリを確保できませんでした。');
    mt.HEAPU8.set(bytes, address);
    return address;
}

function mtGet(mt, address) {
    if (!address) throw new Error('推論結果を読み取れませんでした。');
    return mt.UTF8ToString(address);
}

// Construct this only inside the assistant worker. The key-handling worker
// can continue processing edits while this instance runs multi-threaded Emscripten Wasm.
export async function loadAssistEngine(base = import.meta.url, request = fetch, predictionBudgetScale = 1, modelBuffer) {
    const manifest = await loadAssetManifest(base, request);
    const modelName = manifest?.model?.filename || defaultModelName;
    const cache = request === fetch ? await openAssetCache() : null;
    const activeUrls = new Set();

    const [policy, { mt }, model, reverse] = await Promise.all([
        fetchManifestAsset('policy', 'zerocon_web_live_policy.wasm', manifest, base, request, cache, activeUrls, null)
            .then(compileWasmResponse),
        loadLlamaMt(base, request, manifest, cache, activeUrls),
        modelBuffer
            ? Promise.resolve(new Uint8Array(modelBuffer))
            : fetchManifestAsset('model', modelName, manifest, base, request, cache, activeUrls, null).then(readAsset),
        fetchManifestAsset('reverse', `${reverseName}.gz`, manifest, base, request, cache, activeUrls, null).then(async response => {
            if (!response.ok || !response.body) throw new Error('キーボード補正用の辞書を読み込めませんでした。');
            const bytes = new Uint8Array(await new Response(response.body.pipeThrough(new DecompressionStream('gzip'))).arrayBuffer());
            if (bytes.length < 16 || bytes.length > 64 * 1024 * 1024 || decoder.decode(bytes.subarray(0, 4)) !== 'AZR2') {
                throw new Error('キーボード補正用の辞書が壊れています。');
            }
            return bytes;
        }),
    ]);

    mt.FS.mkdirTree('/runtime');
    mt.FS.writeFile(`/runtime/${modelName}`, model);
    mt.FS.writeFile(`/runtime/${reverseName}`, reverse);

    const path = mtPut(mt, encoder.encode(`/runtime/${modelName}\0`));
    try {
        if (!mt._zerocon_completion_init(path)) throw new Error('補正と先読みのモデルを読み込めませんでした。');
    } finally {
        mt._free(path);
    }

    const wasi = new WASI([], [], [], { debug: false });
    let rawKeys = '';
    let budgetScale = 1;
    function complete(address, timeout) {
        const api = engine.exports;
        try {
            const serialized = get(api, address);
            const payload = JSON.parse(serialized);
            if (payload.keyboard_repair === '1') {
                // The composing host has the romaji ledger. Use it when the
                // Windows fallback only supplied the observed kana.
                const withRaw = serialized.replace('"raw":""', `"raw":"${rawKeys}"`);
                const input = mtPut(mt, encoder.encode(`${withRaw}\0`));
                try {
                    const result = mt._zerocon_keyboard_repair(input, timeout);
                    return result ? put(api, encoder.encode(`${mtGet(mt, result)}\0`)) : 0;
                } finally {
                    mt._free(input);
                }
            }
            const { prompt, n_predict: limit, stop } = payload;
            if (typeof prompt !== 'string' || prompt.includes('\0') || !Number.isInteger(limit)
                || !Array.isArray(stop) || stop.some(s => typeof s !== 'string' || s.includes('\0'))) return 0;
            const promptAddress = mtPut(mt, encoder.encode(`${prompt}\0`));
            let stopsAddress = 0;
            try {
                const markers = encoder.encode(stop.map(s => `${s}\0`).join(''));
                stopsAddress = mtPut(mt, markers.length ? markers : new Uint8Array(1));
                const budget = Math.min(5000, Math.round(timeout * budgetScale));
                const result = mt._zerocon_complete(promptAddress, limit, budget, stopsAddress, markers.length);
                if (!result) return 0;
                const reason = ['error', 'eos', 'stop', 'limit'][mt._zerocon_completion_stop()];
                const response = { content: mtGet(mt, result), stop_type: reason,
                    stopped_limit: reason === 'limit', stopped_eos: reason === 'eos' };
                return put(api, encoder.encode(`${JSON.stringify(response)}\0`));
            } finally {
                mt._free(stopsAddress);
                mt._free(promptAddress);
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
        budgetScale = payload.op === 'prediction' ? predictionBudgetScale : 1;
        try {
            return call(JSON.stringify(payload));
        } finally {
            rawKeys = '';
            budgetScale = 1;
        }
    };
}
