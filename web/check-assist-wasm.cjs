const assert = require('node:assert/strict');
const { readFile } = require('node:fs/promises');
const { WASI } = require('node:wasi');

async function main() {
    const wasmPath = process.argv[2];
    if (!wasmPath) throw new Error('Pass the path to zerocon_engine.wasm');

    const wasi = new WASI({ version: 'preview1' });
    const { instance } = await WebAssembly.instantiate(await readFile(wasmPath), wasi.getImportObject());
    wasi.initialize(instance);
    const api = instance.exports;

    function plan(reading, baselineReading) {
        const input = Buffer.from(JSON.stringify({
            op: 'plan', correctionEnabled: true, predictionEnabled: true, reading, baselineReading,
        }) + '\0');
        const address = api.malloc(input.length);
        assert.notEqual(address, 0);
        try {
            new Uint8Array(api.memory.buffer).set(input, address);
            const response = api.zerocon_assist_policy(address);
            assert.notEqual(response, 0);
            try {
                const memory = new Uint8Array(api.memory.buffer);
                const end = memory.indexOf(0, response);
                assert.ok(end >= 0);
                return JSON.parse(Buffer.from(memory.subarray(response, end)).toString('utf8'));
            } finally {
                api.zerocon_engine_free(response);
            }
        } finally {
            api.free(address);
        }
    }

    assert.deepEqual(plan('しますた', ''), { correction: true, prediction: true });
    assert.deepEqual(plan('しますた', 'しました'), { correction: false, prediction: true });
    process.stdout.write('Shared Rust assist policy passed both WASI checks.\n');
}

main().catch(error => {
    process.stderr.write(`${error.stack}\n`);
    process.exitCode = 1;
});
