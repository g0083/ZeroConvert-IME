const status = document.querySelector('#status');
const results = document.querySelector('#results');
const button = document.querySelector('#run');
const encoder = new TextEncoder();
const decoder = new TextDecoder();

const cases = [
    { name: '観測した読み「しますた」', reading: 'しますた', baselineReading: '', correction: true },
    { name: '変換側が読みを「しました」に補正済み', reading: 'しますた', baselineReading: 'しました', correction: false },
];

async function loadPolicy() {
    const response = await fetch(new URL('./zerocon_engine.wasm', import.meta.url));
    if (!response.ok) throw new Error(`Wasm の取得に失敗しました（HTTP ${response.status}）。`);
    if (response.headers.get('Content-Type')?.split(';')[0].trim() !== 'application/wasm') {
        throw new Error('配信された Wasm の Content-Type が application/wasm ではありません。');
    }
    const compiled = await WebAssembly.compileStreaming(response);
    const imports = { wasi_snapshot_preview1: {} };
    // The pure plan operation needs no filesystem, environment or process I/O.
    // Any accidental use of the rest of the native WASI surface fails the test.
    for (const { module, name, kind } of WebAssembly.Module.imports(compiled)) {
        if (module !== 'wasi_snapshot_preview1' || kind !== 'function') {
            throw new Error(`未対応の Wasm インポート ${module}.${name}`);
        }
        imports[module][name] = () => { throw new Error(`検証中に WASI の ${name} が呼ばれました。`); };
    }
    const instance = await WebAssembly.instantiate(compiled, imports);
    return instance.exports;
}

function plan(api, { reading, baselineReading }) {
    const input = encoder.encode(`${JSON.stringify({
        op: 'plan', correctionEnabled: true, predictionEnabled: true, reading, baselineReading,
    })}\0`);
    const address = api.malloc(input.length);
    if (!address) throw new Error('Wasm で入力メモリを確保できませんでした。');
    try {
        new Uint8Array(api.memory.buffer).set(input, address);
        const result = api.zerocon_assist_policy(address);
        if (!result) throw new Error('エンジンから判定が返りませんでした。');
        try {
            const memory = new Uint8Array(api.memory.buffer);
            const end = memory.indexOf(0, result);
            if (end === -1) throw new Error('エンジンの応答を読み取れませんでした。');
            return JSON.parse(decoder.decode(memory.subarray(result, end)));
        } finally {
            api.zerocon_engine_free(result);
        }
    } finally {
        api.free(address);
    }
}

async function run() {
    button.disabled = true;
    status.dataset.state = 'loading';
    status.textContent = 'WebAssembly を読み込み中…';
    results.replaceChildren();
    try {
        const api = await loadPolicy();
        for (const test of cases) {
            const verdict = plan(api, test);
            if (verdict.correction !== test.correction || verdict.prediction !== true) {
                throw new Error(`${test.name} の判定が期待値と異なります。`);
            }
            const item = document.createElement('li');
            item.textContent = `${test.name} — 補正${verdict.correction ? 'あり' : 'なし'}、先読みあり`;
            results.append(item);
        }
        status.dataset.state = 'pass';
        status.textContent = '共有エンジンの Wasm を読み込み、2 件の判定に成功しました。';
    } catch (error) {
        status.dataset.state = 'error';
        status.textContent = error.message;
    } finally {
        button.disabled = false;
    }
}

button.addEventListener('click', run);
run();
