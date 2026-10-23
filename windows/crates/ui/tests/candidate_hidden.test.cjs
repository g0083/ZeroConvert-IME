const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('hidden WebView renders completions and reports height without animation frames', () => {
    class Element {
        constructor() { this.children = []; this.style = {}; this.attrs = {}; this.offsetHeight = 0; this.scrollTop = 0; }
        appendChild(child) { this.children.push(child); }
        removeChild(child) { this.children = this.children.filter(c => c !== child); }
        replaceChildren(fragment) { this.children = fragment ? fragment.children : []; }
        setAttribute(k, v) { this.attrs[k] = v; }
        removeAttribute(k) { delete this.attrs[k]; }
        hasAttribute(k) { return k in this.attrs; }
        querySelector() { return this.children.find(c => c.className === 'candidate-item'); }
        addEventListener() {}
    }
    const list = new Element(), main = new Element(), footer = new Element(), body = new Element();
    const messages = [], listeners = {};
    const window = {
        ipc: { postMessage: value => messages.push(JSON.parse(value)) },
        requestAnimationFrame() { throw new Error('hidden animation frame must not be required'); },
        addEventListener(name, callback) { listeners[name] = callback; },
        getComputedStyle() { return {paddingTop: '6px', paddingBottom: '6px'}; },
    };
    const document = {
        body,
        getElementById: () => list,
        querySelector: selector => selector === 'main' ? main : footer,
        createElement: () => new Element(),
        createDocumentFragment: () => new Element(),
    };
    const context = vm.createContext({window, document, CandidateScroller: require('../src/candidate_scroller.js')});
    const source = fs.readFileSync(path.join(__dirname, '../src/candidate.rs'), 'utf8');
    const script = source.match(/<script>([\s\S]*?)<\/script>/)[1].replace('__CANDIDATE_SCROLLER_SCRIPT__', '');
    vm.runInContext(script, context);
    listeners.DOMContentLoaded();
    vm.runInContext("updateCandidates(['hel', 'help', 'hello'], 0)", context);
    assert.equal(list.children.length, 3);
    assert.equal(list.children[1].children[0].textContent, 'help');
    assert.ok(messages.some(m => m.type === 'candidate-ready'));
    assert.ok(messages.some(m => m.type === 'resize' && m.height >= 3 * 34));
    vm.runInContext('setCandidateListVisible(false); setCandidateListVisible(true)', context);
    assert.equal(list.children.length, 3);
});
