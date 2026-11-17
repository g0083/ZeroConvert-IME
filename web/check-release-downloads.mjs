import assert from 'node:assert/strict';
import { loadLatestDownloads } from './pages/release-downloads.mjs';

const latest = 'https://api.github.com/repos/g0083/ZeroConvert-IME/releases/latest';
const download = 'https://github.com/g0083/ZeroConvert-IME/releases/download/';
const windows = 'ZeroConvert-IME-setup-v1.2.3.exe';
const android = 'ZeroConvert-IME-v1.2.3-universal-debug.apk';
const uploaded = name => ({ name, state: 'uploaded' });
const publication = (tag_name, assets) => async (url, { cache }) => {
    assert.equal(url, latest);
    assert.equal(cache, 'no-cache');
    return Response.json({ tag_name, assets });
};

assert.deepEqual(await loadLatestDownloads(publication('v1.2.3', [
    uploaded('ZeroConvert-IME-setup-v0.7.0.exe'),
    uploaded(windows), uploaded('ZeroConvert-IME-v1.2.3-arm64-debug.apk'), uploaded(android),
])), {
    windows: `${download}v1.2.3/${windows}`,
    android: `${download}v1.2.3/${android}`,
});
assert.deepEqual(await loadLatestDownloads(publication('1.2.3', [
    uploaded(windows), { name: android, state: 'new' },
])), { windows: `${download}1.2.3/${windows}` });
assert.deepEqual(await loadLatestDownloads(async () => new Response('', { status: 403 })), {});
assert.deepEqual(await loadLatestDownloads(async () => { throw new Error('Offline'); }), {});
console.log('Download links follow the release tag and fall back when assets or metadata are unavailable.');
