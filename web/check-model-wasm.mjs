import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const directory = process.argv[2];
if (!directory) throw new Error('Pass the staged site directory');
const parent = pathToFileURL(`${resolve(directory)}/`);
const { loadLivePolicy, loadModelEngine } = await import(new URL('dictionary-engine.mjs', parent));
const base = process.argv[3];
const read = base ? fetch : async url => new Response(await readFile(url), {
    headers: { 'Content-Type': url.pathname.endsWith('.wasm') ? 'application/wasm' : 'application/octet-stream' },
});
const request = await loadModelEngine(base, read);
let state;
for (const value of 'ashitanokaigi') state = request('append', { value });
assert.equal(state.raw, 'ashitanokaigi');
assert.equal(state.reading, 'あしたのかいぎ');
assert.match(state.modelStatus, /^load file:\/\/\/runtime\/models\/zerocon-nano-v4\.2-iq4nl-emb8\.gguf$/);
assert.equal(state.candidates[0].text + state.candidates[0].suffix, '明日の会議');
assert.equal(request('move', { offset: -1 }).cursor, 6);
assert.equal(request('move', { offset: 1 }).cursor, 7);
assert.equal(request('backspace').reading, 'あしたのかい');
assert.equal(request('clear').reading, '');

const batch = request('append', { value: 'ashitanokaigi' });
assert.equal(batch.raw, 'ashitanokaigi');
assert.equal(batch.reading, 'あしたのかいぎ');
assert.equal(batch.candidates[0].text + batch.candidates[0].suffix, '明日の会議');
assert.equal(request('clear').reading, '');

assert.equal(request('appendDirect', { value: 'か' }).reading, 'か');
assert.equal(request('appendDirect', { value: 'な' }).reading, 'かな');
assert.equal(request('move', { offset: -1 }).cursor, 1);
assert.equal(request('replacePreviousDirect', { value: 'が' }).reading, 'がな');
assert.equal(request('backspace').reading, 'な');
assert.equal(request('clear').reading, '');
const kanaBatch = request('appendDirect', { value: 'にほんご' });
assert.equal(kanaBatch.raw, 'にほんご');
assert.equal(kanaBatch.reading, 'にほんご');
assert.equal(kanaBatch.candidates[0].text, '日本語');
assert.equal(request('backspace').reading, 'にほん');

const live = await loadLivePolicy(base, read);
const fixtures = await readFile(new URL('../tests/input-policy/auto-commit-cases.tsv', import.meta.url), 'utf8');
const stable = fixtures.split(/\r?\n/).find(line => line.startsWith('auto_commit_stable_clause\t'));
const [, reading, , history, expected] = stable.split('\t');
let prefix;
for (const entry of history.split(';')) {
    const [length, preview] = entry.split(':');
    const snapshotReading = [...reading].slice(0, Number(length)).join('');
    prefix = live.observe({ raw: snapshotReading, reading: snapshotReading, preview, suffix: '' });
}
assert.equal(`${prefix.rawCount}:${prefix.surface}`, expected);
live.reset();
assert.equal(live.observe({ raw: reading, reading, preview: '私は学校に行って勉強しています', suffix: '' }), null);
process.stdout.write('Windows Swift conversion loaded ZeroCon Nano and passed input, candidates, cursor, deletion, reset and shared automatic commit checks.\n');
