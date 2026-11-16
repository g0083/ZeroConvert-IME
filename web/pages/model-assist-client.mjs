// Keep one Wasm call in flight and at most the latest edit's requests queued.
// Invalidation does not interrupt synchronous Wasm; the model enforces a deadline.
export function createAssistClient(reportLoad) {
    const worker = new Worker(new URL('./model-assist-worker.mjs', import.meta.url), { type: 'module' });
    reportLoad?.('loading');
    let current = null;
    let queued = [];
    let revision = 0;
    let id = 0;
    let failed = false;

    function dispatch() {
        if (current || !queued.length || failed) return;
        current = queued.shift();
        worker.postMessage({ id: current.id, input: current.input });
    }

    worker.addEventListener('message', ({ data }) => {
        if (data.loadState) {
            reportLoad?.(data.loadState);
            if (data.loadState === 'failed') stop();
            return;
        }
        if (current?.id !== data.id) return;
        current.resolve(current.input.revision === revision ? data.answer : null);
        current = null;
        dispatch();
    });
    worker.addEventListener('error', event => {
        event.preventDefault();
        reportLoad?.('failed');
        stop();
    });

    function stop() {
        failed = true;
        current?.resolve(null);
        for (const pending of queued) pending.resolve(null);
        current = null;
        queued = [];
        worker.terminate();
    }

    function request(input) {
        if (failed || input.revision < revision) return Promise.resolve(null);
        request.invalidate(input.revision);
        return new Promise(resolve => {
            queued.push({ id: ++id, input, resolve });
            dispatch();
        });
    }
    request.invalidate = next => {
        if (next <= revision) return;
        revision = next;
        for (const pending of queued) pending.resolve(null);
        queued = [];
    };
    return request;
}
