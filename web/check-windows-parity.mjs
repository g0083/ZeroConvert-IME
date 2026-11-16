// Replay a Windows DLL trace against the staged Swift Wasm engine.
import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const [site, traceFile] = process.argv.slice(2);
if (!site || !traceFile) throw new Error('Pass a staged site directory and Windows trace JSON');
const base = pathToFileURL(resolve(site) + '/');
const trace = JSON.parse(await readFile(traceFile, 'utf8'));
if (trace.schema !== 2 || trace.layer !== 'windows-swift-dll') throw new Error('Unsupported Windows trace');
const digest = async name => createHash('sha256').update(await readFile(new URL(name, base))).digest('hex');
const matches = await Promise.all([
    digest('ime-settings.json').then(hash => hash === trace.settingsSha256),
    digest('dictionary.gz').then(hash => hash === trace.dictionarySha256),
    digest(trace.model).then(hash => hash === trace.modelSha256),
]);
if (matches.includes(false)) throw new Error('The trace and staged site use different settings, dictionaries or models');
const { loadModelEngine } = await import(new URL('dictionary-engine.mjs', base));
const request = await loadModelEngine(undefined, async url => new Response(await readFile(url), {
    headers: { 'Content-Type': url.pathname.endsWith('.wasm') ? 'application/wasm' : 'application/octet-stream' },
}));

let total = 0;
let divergent = 0;
const timings = { windows: [], web: [] };
for (const { name, steps } of trace.cases) {
    request('clear');
    request('context', { value: '' });
    let first = true;
    for (const [offset, step] of steps.entries()) {
        const { command: { key, ...fields }, state: windows } = step;
        total += 1;
        const started = performance.now();
        const { reading, raw, cursor, candidates, adjustedInputCount } = request(key, fields);
        timings.web.push(performance.now() - started);
        timings.windows.push(step.elapsedMs);
        const web = { reading, raw, cursor, candidates: candidates.map(row => ({
            text: row.text, suffix: row.suffix, reading: row.reading, inputCount: row.inputCount,
            predictionReading: row.predictionReading,
        })) };
        if (key === 'adjustClause') web.adjustedInputCount = adjustedInputCount ?? null;
        const changed = Object.keys(windows).filter(field => !isDeepStrictEqual(web[field], windows[field]));
        if (!changed.length) continue;
        divergent += 1;
        if (!first) continue;
        first = false;
        console.error(`${name} #${offset + 1} (${key}): ${changed.join(', ')}`);
        for (const field of changed) {
            if (field !== 'candidates') {
                console.error(`  ${field}: Windows ${JSON.stringify(windows[field])}, Web ${JSON.stringify(web[field])}`);
                continue;
            }
            const row = windows.candidates.findIndex((candidate, i) => !isDeepStrictEqual(candidate, web.candidates[i]));
            console.error(`  row count: Windows ${windows.candidates.length}, Web ${web.candidates.length}`);
            if (row !== -1) console.error(`  row ${row + 1}: Windows ${JSON.stringify(windows.candidates[row])}, Web ${JSON.stringify(web.candidates[row])}`);
        }
    }
}
process.stdout.write(`${total - divergent}/${total} Windows DLL steps matched the Web engine.\n`);
for (const [platform, values] of Object.entries(timings)) {
    if (!values.length) continue;
    values.sort((a, b) => a - b);
    const percentile = p => values[Math.ceil(values.length * p) - 1].toFixed(1);
    console.log(`${platform} conversion and row serialization, p50 ${percentile(.5)} ms, p95 ${percentile(.95)} ms`);
}
if (divergent || !total) process.exitCode = 1;
