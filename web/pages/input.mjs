const input = document.querySelector('#romaji');
const status = document.querySelector('#conversion-status');
const reading = document.querySelector('#reading');
const list = document.querySelector('#candidates');
const sample = document.querySelector('#sample');
const output = document.querySelector('#confirmed');

let revision = 0;
let selected = 0;
let candidates = [];
const worker = new Worker(new URL('./dictionary-worker.mjs', import.meta.url), { type: 'module' });

function render() {
    list.replaceChildren();
    for (const [index, candidate] of candidates.entries()) {
        const item = document.createElement('li');
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = candidate;
        button.dataset.selected = String(index === selected);
        button.addEventListener('click', () => commit(candidate));
        item.append(button);
        list.append(item);
    }
}

function clearComposition() {
    ++revision;
    input.value = '';
    reading.textContent = '';
    candidates = [];
    render();
}

function commit(candidate) {
    output.value += candidate;
    clearComposition();
    input.focus();
}

function update(event) {
    if (event?.isComposing) return;
    reading.textContent = '';
    candidates = [];
    selected = 0;
    render();
    ++revision;
    if (input.value) worker.postMessage({ revision, raw: input.value });
}

worker.addEventListener('message', ({ data }) => {
    if (data.type === 'ready') {
        input.disabled = false;
        sample.disabled = false;
        status.textContent = '共有辞書で変換できます。';
    } else if (data.type === 'failed' || (data.type === 'conversion-error' && data.revision === revision)) {
        status.textContent = `エンジンを実行できませんでした。${data.message}`;
    } else if (data.type === 'conversion' && data.revision === revision) {
        status.textContent = '共有辞書で変換できます。';
        reading.textContent = data.reading;
        candidates = data.candidates;
        render();
    }
});
worker.addEventListener('error', () => { status.textContent = '変換エンジンを読み込めませんでした。'; });

input.addEventListener('input', update);
input.addEventListener('compositionend', update);
input.addEventListener('keydown', event => {
    if (event.isComposing || event.ctrlKey || event.altKey || event.metaKey) return;
    if (event.key === 'Escape') clearComposition();
    else if (candidates.length && event.key === 'Enter') commit(candidates[selected]);
    else if (candidates.length && [' ', 'ArrowDown', 'ArrowUp'].includes(event.key)) {
        selected = (selected + candidates.length + (event.key === 'ArrowUp' ? -1 : 1)) % candidates.length;
        render();
    } else return;
    event.preventDefault();
});

sample.addEventListener('click', () => {
    input.value = 'ashitanokaigi';
    input.focus();
    update();
});
