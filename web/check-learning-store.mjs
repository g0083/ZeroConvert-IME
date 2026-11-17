import assert from 'node:assert/strict';
import { openLearningStore, validLearningFiles } from './pages/learning-store.mjs';

assert.equal(validLearningFiles([['selection-overrides.json', new Uint8Array([123, 125])]]), true);
assert.equal(validLearningFiles([['../settings.json', new Uint8Array()]]), false);
assert.equal(validLearningFiles([['a', new Uint8Array()], ['a', new Uint8Array()]]), false);
assert.equal(validLearningFiles([['a', new Uint8Array(16 * 1024 * 1024 + 1)]]), false);
assert.equal(validLearningFiles([['a', 'not bytes']]), false);
assert.equal(await openLearningStore(null), null);
console.log('Unsafe learning snapshots are rejected and a missing store does not block the engine.');
