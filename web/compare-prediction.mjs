// Replay the JSON lines printed by the shared Rust live_prediction_evaluation test.
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const [site, nativeLog, budgetArgument = '1'] = process.argv.slice(2);
if (!site || !nativeLog) throw new Error('Pass a staged site directory, native evaluation log and optionally a budget scale');
const budgetScale = Number(budgetArgument);
if (!Number.isFinite(budgetScale) || budgetScale < 1 || budgetScale > 5) throw new Error('Budget scale must be between 1 and 5');
const base = pathToFileURL(resolve(site) + '/');
const tests = (await readFile(nativeLog, 'utf8')).split(/\r?\n/u)
    .map(line => line.trim()).filter(line => line.startsWith('{'))
    .map(line => JSON.parse(line));
if (!tests.length) throw new Error('The native evaluation produced no cases');
const { loadAssistEngine } = await import(new URL('dictionary-engine.mjs', base));
const request = await loadAssistEngine(undefined, async url => new Response(await readFile(url), {
    headers: { 'Content-Type': url.pathname.endsWith('.wasm') ? 'application/wasm' : 'application/octet-stream' },
}), budgetScale);
for (const { reading, surface, continuation, elapsed_ms: windowsMs } of tests) {
    const started = performance.now();
    const response = request({ op: 'prediction', input: reading, baseline: surface, context: '' });
    console.log(JSON.stringify({
        reading, surface, windows: continuation, web: response?.continuation ?? null,
        windowsMs, webMs: Math.round(performance.now() - started), budgetScale,
        match: (response?.continuation ?? null) === continuation,
    }));
}
