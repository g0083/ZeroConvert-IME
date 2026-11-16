import { loadAssistEngine } from './dictionary-engine.mjs';

// The single-threaded Wasm backend needs more time than the native backend.
const ready = loadAssistEngine(undefined, undefined, 3);
ready.then(
    () => postMessage({ loadState: 'ready' }),
    () => postMessage({ loadState: 'failed' }),
);
self.addEventListener('message', async ({ data: { id, input } }) => {
    try {
        const request = await ready;
        postMessage({ id, answer: request(input) });
    } catch {
        postMessage({ id, answer: null });
    }
});
