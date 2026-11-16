import { loadAssistEngine } from './dictionary-engine.mjs';

const ready = loadAssistEngine();
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
