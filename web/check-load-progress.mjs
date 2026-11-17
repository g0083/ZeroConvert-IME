import assert from 'node:assert/strict';
import { trackAsset } from './pages/load-progress.mjs';

for (const encoding of [null, 'br']) {
    const events = [];
    const input = new Uint8Array([1, 2, 3, 4, 5]);
    const headers = { 'Content-Type': 'application/wasm', 'Content-Length': '5' };
    if (encoding) headers['Content-Encoding'] = encoding;
    const response = await trackAsset('model', () => new Response(input, { headers }), event => events.push(event));
    assert.deepEqual(new Uint8Array(await response.arrayBuffer()), input);
    assert.equal(response.headers.get('Content-Type'), 'application/wasm');
    assert.deepEqual(events[0], { asset: 'model', phase: 'downloading', received: 0, total: null });
    assert.deepEqual(events[1], { asset: 'model', phase: 'downloading', received: 5, total: encoding ? null : 5 });
    assert.deepEqual(events.at(-1), { asset: 'model', phase: 'downloaded', received: 5 });
}
const events = [];
const missing = await trackAsset('model', () => new Response('missing', { status: 404 }), event => events.push(event));
assert.equal(missing.status, 404);
assert.equal(events.some(event => event.phase === 'downloaded'), false);
console.log('Load progress counts raw streams, preserves Wasm MIME types, and does not invent totals for HTTP compression.');
