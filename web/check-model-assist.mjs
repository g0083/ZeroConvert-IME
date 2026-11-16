import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

if (!process.argv[2]) throw new Error('Pass the staged site directory');
const base = pathToFileURL(resolve(process.argv[2]) + '/');
const { loadAssistEngine } = await import(new URL('dictionary-engine.mjs', base));
// Check the model's output independently of CPU scheduling on shared CI runners.
const completionBudgetScale = 3;
const request = await loadAssistEngine(undefined, async url => new Response(await readFile(url), {
    headers: { 'Content-Type': url.pathname.endsWith('.wasm') ? 'application/wasm' : 'application/octet-stream' },
}), completionBudgetScale);

const correctionStart = performance.now();
const miskey = request({ op: 'correction', raw: 'okseri', input: 'おkせり', baseline: 'おkせり', context: '' });
const correctionMs = Math.round(performance.now() - correctionStart);
assert.equal(miskey?.text, 'おかえり');
assert.equal(request({ op: 'correction', raw: 'okaeri', input: 'おかえり', baseline: 'おかえり', context: '' }), null);
const predictionStart = performance.now();
const next = request({ op: 'prediction', input: 'よろしくおねがいし', baseline: 'よろしくお願いし', context: '前文。' });
const predictionMs = Math.round(performance.now() - predictionStart);
assert.equal(next?.continuation, 'ます。');
assert.equal(next?.text, 'よろしくお願いします。');
console.log('The Nano model corrected the mistyped reading, left the control alone, and completed the contextual phrase.');
console.log(`Wasm call time: correction ${correctionMs} ms, context prediction ${predictionMs} ms`);
