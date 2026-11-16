import { loadLivePolicy, loadModelEngine } from './dictionary-engine.mjs';

const loading = Promise.all([loadModelEngine(), loadLivePolicy()]);
let state;
let committed = '';
let selection = null;
loading.then(([request]) => {
    state = request('clear');
    postMessage({ type: 'ready', state });
}).catch(error => postMessage({ type: 'failed', message: error.message }));

function autoCommit(request, live) {
    const top = state.candidates[0];
    if (!request.autoCommit || !top || state.cursor !== [...state.reading].length) return;
    const prefix = live.observe({ raw: state.raw, reading: state.reading, preview: top.text, suffix: top.suffix });
    if (!prefix) return;
    state = request('shrink', { offset: prefix.rawCount });
    const next = state.candidates[0]?.text;
    const boundary = next && top.text.endsWith(next) ? top.text.length - next.length : 0;
    committed += boundary ? top.text.slice(0, boundary) : prefix.surface;
    state = request('context', { value: [...committed].slice(-256).join('') });
    live.reset();
}

function commit(request, candidate, partial) {
    const text = candidate ? candidate.text + (partial ? '' : candidate.suffix) : state.reading;
    committed += text;
    selection = null;
    if (partial && candidate.inputCount > 0 && candidate.inputCount < [...state.raw].length) {
        state = request('shrink', { offset: candidate.inputCount });
    } else {
        state = request('clear');
    }
    state = request('context', { value: [...committed].slice(-256).join('') });
}

self.addEventListener('message', async ({ data: { revision, key, value } }) => {
    try {
        const [request, live] = await loading;
        if (key !== 'append' || selection !== null) live.reset();
        if (key === 'append' || key === 'sample') {
            selection = null;
            if (key === 'sample') {
                state = request('clear');
                value = 'ashitanokaigi';
            }
            for (const char of value) {
                if ([...state.raw].length >= 256) break;
                state = request('append', { value: char });
                if (key === 'append') autoCommit(request, live);
            }
        } else if (key === 'space' || key === 'up' || key === 'down') {
            const count = state.candidates.length;
            if (count) selection = selection === null ? (key === 'up' ? count - 1 : 0)
                : (selection + count + (key === 'up' ? -1 : 1)) % count;
            else if (key === 'space' && !state.reading) {
                committed += ' ';
                state = request('context', { value: [...committed].slice(-256).join('') });
            }
        } else if (key === 'select' && value && state.candidates[value.index]?.id === value.id) {
            commit(request, state.candidates[value.index], true);
        } else if (key === 'enter') {
            if (state.reading) commit(request, state.candidates[selection ?? 0], selection !== null);
            else {
                committed += '\n';
                state = request('context', { value: [...committed].slice(-256).join('') });
            }
        } else if (key === 'cancel' && selection !== null) {
            selection = null;
        } else if (key === 'cancel') {
            state = request('clear');
        } else if (key === 'backspace' || key === 'delete' || key === 'left' || key === 'right') {
            selection = null;
            if (!state.reading && key === 'backspace') {
                committed = [...committed].slice(0, -1).join('');
                state = request('context', { value: [...committed].slice(-256).join('') });
            } else if (state.reading) {
                state = key === 'left' || key === 'right' ? request('move', { offset: key === 'left' ? -1 : 1 }) : request(key);
            }
        }
        postMessage({ type: 'state', revision, state, selection, committed });
    } catch (error) {
        postMessage({ type: 'error', revision, message: error.message });
    }
});
