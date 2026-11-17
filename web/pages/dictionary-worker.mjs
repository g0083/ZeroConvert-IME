import { loadDictionaryEngine } from './dictionary-engine.mjs';

const loading = loadDictionaryEngine();
loading.then(() => postMessage({ type: 'ready' }), error => postMessage({ type: 'failed', message: error.message }));

self.addEventListener('message', async ({ data: { revision, raw } }) => {
    try {
        const convert = await loading;
        postMessage({ type: 'conversion', revision, ...convert(raw) });
    } catch (error) {
        postMessage({ type: 'conversion-error', revision, message: error.message });
    }
});
