import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

if (!process.argv[2]) throw new Error('Pass the staged site directory');
const base = pathToFileURL(resolve(process.argv[2]) + '/');
const { loadModelEngine } = await import(new URL('dictionary-engine.mjs', base));
const read = async url => new Response(await readFile(url), {
    headers: { 'Content-Type': url.pathname.endsWith('.wasm') ? 'application/wasm' : 'application/octet-stream' },
});
const first = await loadModelEngine(undefined, read);
first.applySettings({ learning: 'enabled' });
const convert = engine => {
    engine('clear');
    return engine('append', { value: 'hashi' });
};
let state = convert(first);
const before = state.candidates[0].text;
const chosen = state.candidates.find(row => row.learningAllowed && row.text !== before && row.suffix === '');
assert.ok(chosen, 'need an ordinary, learnable candidate other than the first');
assert.equal(first('recordLearning', { candidateId: chosen.id, offset: 1 }).learningChanged, true);
assert.equal(convert(first).candidates[0].text, chosen.text);
const files = first.learningFiles();
assert.ok(files.some(([name]) => name === 'selection-overrides.json'));

const restored = await loadModelEngine(undefined, read, files);
assert.equal(convert(restored).candidates[0].text, before, 'disabled ignores the persisted selection');
restored.applySettings({ learning: 'read_only' });
state = convert(restored);
assert.equal(state.candidates[0].text, chosen.text, 'the learned candidate is promoted after reload');
assert.deepEqual(restored.learningFiles(), files, 'read-only conversion does not rewrite the saved memory');
// Swift only issues learning IDs in the mode that records conversions.
restored.applySettings({ learning: 'enabled' });
state = convert(restored);
assert.equal(restored('forgetLearning', { candidateId: state.candidates[0].id }).learningChanged, true);
assert.equal(convert(restored).candidates[0].text, before, 'forgetting restores the original first candidate');
assert.equal(restored('clearLearning').learningChanged, true);
assert.equal(restored.learningFiles().length, 0);
console.log('Swift learning changed rank, survived reload, and supported forgetting and clearing.');
