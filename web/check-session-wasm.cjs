// Verifies that the Web Worker's candidate selection resolves through the same
// shared policy the Windows client uses. Run after
// `cargo rustc --manifest-path web/live-policy/Cargo.toml --target wasm32-wasip1`.
const assert = require('node:assert/strict');
const { readFile } = require('node:fs/promises');
const { WASI } = require('node:wasi');

async function main() {
    const wasmPath = process.argv[2] ?? 'web/live-policy/target/wasm32-wasip1/release/zerocon_web_live_policy.wasm';
    const wasi = new WASI({ version: 'preview1' });
    const { instance } = await WebAssembly.instantiate(await readFile(wasmPath), {
        ...wasi.getImportObject(), zerocon_host: { complete: () => 0 },
    });
    wasi.initialize(instance);
    const api = instance.exports;
    const encoder = new TextEncoder();
    const decoder = new TextDecoder();

    function selection(request) {
        const input = encoder.encode(`${JSON.stringify(request)}\0`);
        const address = api.malloc(input.length);
        assert.notEqual(address, 0);
        try {
            new Uint8Array(api.memory.buffer).set(input, address);
            const response = api.zerocon_session_selection(address);
            assert.notEqual(response, 0, `no result for ${JSON.stringify(request)}`);
            try {
                const memory = new Uint8Array(api.memory.buffer);
                const end = memory.indexOf(0, response);
                assert.ok(end >= 0);
                return JSON.parse(decoder.decode(memory.subarray(response, end)));
            } finally {
                api.zerocon_live_free(response);
            }
        } finally {
            api.free(address);
        }
    }

    // The Windows client clamps instead of wrapping.
    assert.equal(selection({ current: 0, candidates: 3, direction: 'next' }), 1);
    assert.equal(selection({ current: 2, candidates: 3, direction: 'next' }), 2);
    assert.equal(selection({ current: 1, candidates: 3, direction: 'previous' }), 0);
    assert.equal(selection({ current: 0, candidates: 3, direction: 'previous' }), 0);
    assert.equal(selection({ current: 0, candidates: 3, direction: 'absolute', index: 9 }), 2);
    assert.equal(selection({ current: 0, candidates: 3, direction: 'absolute', index: -1 }), 0);
    assert.equal(selection({ current: 0, candidates: 0, direction: 'next' }), null);
    assert.equal(selection({ current: 9, candidates: 3, direction: 'next' }), 2);

    function decision(request) {
        const input = encoder.encode(`${JSON.stringify(request)}\0`);
        const address = api.malloc(input.length);
        assert.notEqual(address, 0);
        try {
            new Uint8Array(api.memory.buffer).set(input, address);
            const response = api.zerocon_session_decision(address);
            assert.notEqual(response, 0);
            try {
                const memory = new Uint8Array(api.memory.buffer);
                const end = memory.indexOf(0, response);
                return JSON.parse(decoder.decode(memory.subarray(response, end)));
            } finally {
                api.zerocon_live_free(response);
            }
        } finally {
            api.free(address);
        }
    }

    // The same accept boundaries the Windows client plans for Enter.
    assert.deepEqual(decision({ action: 'accept', cursor: 4, total: 4, has_prefix: true, has_suffix: false }), { accept: 'whole' });
    assert.deepEqual(decision({ action: 'accept', cursor: 0, total: 3, has_prefix: false, has_suffix: true }), { accept: 'suffix' });
    assert.deepEqual(decision({ action: 'accept', cursor: 2, total: 5, has_prefix: true, has_suffix: true }), { accept: 'prefix' });
    // Backspace and Delete share the Windows cursor rules.
    assert.deepEqual(decision({ action: 'backspace', cursor: 0, total: 3 }), { delete: 'none' });
    assert.deepEqual(decision({ action: 'backspace', cursor: 2, total: 3 }), { delete: 'backward' });
    assert.deepEqual(decision({ action: 'delete', cursor: 3, total: 3 }), { delete: 'none' });
    assert.deepEqual(decision({ action: 'delete', cursor: 1, total: 3 }), { delete: 'forward' });

    function assist(request) {
        const input = encoder.encode(`${JSON.stringify(request)}\0`);
        const address = api.malloc(input.length);
        assert.notEqual(address, 0);
        try {
            new Uint8Array(api.memory.buffer).set(input, address);
            const response = api.zerocon_session_assist(address);
            assert.notEqual(response, 0);
            try {
                const memory = new Uint8Array(api.memory.buffer);
                const end = memory.indexOf(0, response);
                return JSON.parse(decoder.decode(memory.subarray(response, end)));
            } finally {
                api.zerocon_live_free(response);
            }
        } finally {
            api.free(address);
        }
    }

    // Correction owns priority over prediction, and only a correction may be
    // applied automatically. These are the Windows merge rules.
    assert.deepEqual(assist({ op: 'plan', correctionEnabled: true, predictionEnabled: true, reading: 'しますた' }), { correction: true, prediction: true });
    assert.deepEqual(assist({ op: 'plan', correctionEnabled: true, predictionEnabled: true, reading: 'しますた', baselineReading: 'しました' }), { correction: false, prediction: true });
    assert.deepEqual(assist({ op: 'choose', choices: [[60, 999902], [30, 999901]] }), { selected: 999901 });
    assert.deepEqual(assist({ op: 'choose', choices: [[60, 999902]], displayed: 999901 }), { selected: 999901 });
    assert.deepEqual(assist({ op: 'adopt', id: 999902, explicit: false, automatic: true }), { action: 'ignore' });
    assert.deepEqual(assist({ op: 'adopt', id: 999901, explicit: false, automatic: true }), { action: 'replaceComposition' });
    assert.deepEqual(assist({ op: 'ready', predictionDone: true, correctionDone: false, automatic: false, elapsedMs: 199 }), { waitMs: 200, ready: false });
    assert.deepEqual(assist({ op: 'ready', predictionDone: true, correctionDone: false, automatic: false, elapsedMs: 200 }), { waitMs: 200, ready: true });
    assert.equal(assist({ op: 'plan', reading: 'しまし' }).correction, false);
    assert.ok(assist({ op: 'unknown' }).error);

    function learning(request) {
        const input = encoder.encode(`${JSON.stringify(request)}\0`);
        const address = api.malloc(input.length);
        assert.notEqual(address, 0);
        try {
            new Uint8Array(api.memory.buffer).set(input, address);
            const response = api.zerocon_session_learning(address);
            assert.notEqual(response, 0);
            try {
                const memory = new Uint8Array(api.memory.buffer);
                const end = memory.indexOf(0, response);
                return JSON.parse(decoder.decode(memory.subarray(response, end)));
            } finally {
                api.zerocon_live_free(response);
            }
        } finally {
            api.free(address);
        }
    }

    // Learning capabilities follow the Windows mode mapping.
    assert.deepEqual(learning({ mode: 'enabled', target: true }), {
        mode: 'enabled', record: true, read: true, forget: true, clear: true, recordCommit: true,
    });
    assert.deepEqual(learning({ mode: 'read_only', target: true }), {
        mode: 'read_only', record: false, read: true, forget: true, clear: true, recordCommit: false,
    });
    assert.deepEqual(learning({ mode: 'disabled', target: true }), {
        mode: 'disabled', record: false, read: false, forget: false, clear: true, recordCommit: false,
    });
    assert.deepEqual(learning({ mode: ' Read-Only ', target: false }), {
        mode: 'read_only', record: false, read: true, forget: true, clear: true, recordCommit: false,
    });
    assert.deepEqual(learning({ mode: 'enabled', target: false }), {
        mode: 'enabled', record: true, read: true, forget: true, clear: true, recordCommit: false,
    });
    process.stdout.write('Shared session selection, commit, assist and learning policy are exposed to the Web Worker.\n');
}

main().catch(error => {
    process.stderr.write(`${error.stack}\n`);
    process.exitCode = 1;
});
