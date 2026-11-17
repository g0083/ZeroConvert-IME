import { loadAssistEngine } from './dictionary-engine.mjs';

let engine;
self.addEventListener('message', async ({ data: { type, model, id, input } }) => {
    if (type === 'initialize') {
        try {
            // The single-threaded Wasm backend needs more time than the native backend.
            engine = await loadAssistEngine(undefined, undefined, 3, model);
            postMessage({ loadState: 'ready' });
        } catch {
            postMessage({ loadState: 'failed' });
        }
        return;
    }
    try {
        postMessage({ id, answer: engine(input) });
    } catch {
        postMessage({ id, answer: null });
    }
});
