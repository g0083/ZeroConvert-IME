import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const directory = process.argv[2];
if (!directory) throw new Error('Pass the staged site directory or its HTTPS URL');
const online = directory.startsWith('https://');
const base = online ? new URL(directory) : pathToFileURL(`${resolve(directory)}/`);
const localEngine = online ? resolve(process.argv[3], 'dictionary-engine.mjs')
    : resolve(directory, 'dictionary-engine.mjs');
const { loadDictionaryEngine } = await import(pathToFileURL(localEngine));
const request = online ? fetch : async url => new Response(await readFile(url), {
    headers: { 'Content-Type': url.pathname.endsWith('.wasm') ? 'application/wasm' : 'application/gzip' },
});

const convert = await loadDictionaryEngine(base, request);
const raw = 'ashitanokaigi';
for (let length = 1; length < raw.length; length++) {
    assert.ok(convert(raw.slice(0, length)).reading);
}
assert.equal(convert(raw).reading, 'あしたのかいぎ');
assert.equal(convert(raw).candidates[0], '明日の会議');
assert.equal(convert(raw.slice(0, -1)).reading, 'あしたのかいg');
assert.deepEqual(convert(''), { reading: '', candidates: [] });
assert.equal(convert('nihongo').candidates[0], '日本語');
process.stdout.write('Shared Swift dictionary passed incremental input, deletion, reset and candidate checks.\n');
