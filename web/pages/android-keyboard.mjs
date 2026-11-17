import native from './android-keyboard-layout.mjs';

const { geometry, layouts, modifiers, pickers, palettes, qwerty, vibration } = native;
export const androidVibrationDefault = vibration.enabled;
const modes = ['japanese', 'english', 'numeric'];
const modeLabels = ['日本語', '英語', '数字'];
const sides = {
    japanese: [['symbols', 'backspace'], ['left', 'space'], ['right', 'emoji'], ['mode', 'enter']],
    english: [['symbols', 'backspace'], ['left', 'space'], ['right', 'emoji'], ['mode', 'enter']],
    numeric: [['undo', 'backspace'], ['left', 'right'], ['symbols', 'space'], ['mode', 'enter']],
};
const actionKeys = {
    symbols: ['!?#', '記号'], backspace: ['⌫', '削除'], left: ['◀', 'カーソルを左へ'],
    right: ['▶', 'カーソルを右へ'], space: ['␣', '空白'], emoji: ['☺', '絵文字'],
    enter: ['確定', '確定'], undo: ['↶', '削除'], shift: ['⇧', 'Shift'],
};

export function defaultPresentation(device = navigator) {
    const { userAgent, userAgentData, maxTouchPoints } = device;
    // iPadOS may advertise a desktop Safari user agent.
    return userAgentData?.mobile || /Android|iPhone|iPad|iPod|Mobile/u.test(userAgent)
        || (/Macintosh/u.test(userAgent) && maxTouchPoints > 1) ? 'android' : 'windows';
}

export function flickDirection(dx, dy, current) {
    const { flickThreshold, hysteresis, returnRatio } = geometry;
    const exit = current === 'center' ? flickThreshold : flickThreshold * returnRatio;
    const x = Math.abs(dx);
    const y = Math.abs(dy);
    if (x < exit && y < exit) return 'center';
    let horizontal = x > y;
    if (current === 'left' || current === 'right') horizontal = y <= x * hysteresis;
    if (current === 'up' || current === 'down') horizontal = x > y * hysteresis;
    return horizontal ? (dx < 0 ? 'left' : 'right') : (dy < 0 ? 'up' : 'down');
}

function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text) node.textContent = text;
    return node;
}

function button(className, label, action, description) {
    const node = element('button', className, label);
    node.type = 'button';
    if (description) node.setAttribute('aria-label', description);
    node.addEventListener('click', action);
    return node;
}

export function createAndroidKeyboard(root, dispatch, vibrationEnabled) {
    const original = root.querySelector('.android-original');
    const originalValue = original.querySelector('bdi');
    const originalPrefix = original.querySelector('span');
    const proposal = root.querySelector('.android-proposal');
    const proposalTitle = proposal.querySelector('span');
    const proposalValue = proposal.querySelector('bdi');
    const undo = root.querySelector('.android-undo');
    const settings = root.querySelector('.android-settings');
    const layoutToggle = root.querySelector('.android-layout-toggle');
    const strip = root.querySelector('.android-candidates');
    const expand = root.querySelector('.android-expand');
    const board = root.querySelector('.android-keys');
    const panel = root.querySelector('.android-panel');
    let mode = 'japanese';
    let keyboardLayout = 'flick';
    let shifted = false;
    let composing = false;
    let picker = '';
    let expanded = false;
    let rows = [];
    let selected = null;
    let top = '';

    const colorScheme = matchMedia('(prefers-color-scheme: dark)');
    function applyPalette() {
        for (const [key, color] of Object.entries(palettes[colorScheme.matches ? 'dark' : 'light'])) {
            root.style.setProperty(`--android-${key}`, color);
        }
    }
    applyPalette();
    colorScheme.addEventListener('change', applyPalette);
    root.style.setProperty('--android-content-height', `${geometry.keyHeight * layouts.japanese.length + geometry.contentExtraHeight}px`);
    root.style.setProperty('--android-bar-height', `${geometry.keyHeight * geometry.barRatio}px`);
    root.style.setProperty('--android-columns', geometry.columnWeights.map(weight => `${weight}fr`).join(' '));

    root.addEventListener('pointerdown', event => {
        if (event.target.closest('button')) event.preventDefault();
    });
    // Touch pointerup is a trusted activation, including the first key pressed.
    root.addEventListener('pointerup', event => {
        if (event.pointerType === 'touch' && !root.disabled && vibrationEnabled() && event.target.closest('button:enabled')) {
            navigator.vibrate?.(vibration.duration);
        }
    }, { capture: true });
    layoutToggle.addEventListener('click', () => {
        keyboardLayout = keyboardLayout === 'flick' ? 'qwerty' : 'flick';
        shifted = false;
        layoutToggle.textContent = keyboardLayout === 'flick' ? '↻ QWERTY' : '↻ 12キー';
        renderKeys();
    });
    proposal.addEventListener('click', () => dispatch('tab'));
    undo.addEventListener('click', () => dispatch('cancel'));
    expand.addEventListener('click', () => {
        expanded = !expanded;
        picker = '';
        renderPanel();
    });

    function candidate(row, index) {
        const label = (row.answer ? '補正: ' : '') + row.text + row.suffix;
        const node = button('android-candidate', label, () => dispatch('select', { index, id: row.id }));
        node.setAttribute('aria-pressed', String(index === (selected ?? 0)));
        return node;
    }

    function renderPanel() {
        expand.setAttribute('aria-expanded', String(expanded));
        expand.setAttribute('aria-label', expanded ? '候補一覧を閉じる' : '候補一覧を開く');
        expand.textContent = expanded ? '⌃' : '⌄';
        board.hidden = expanded || Boolean(picker);
        panel.hidden = !board.hidden;
        if (expanded) {
            const grid = element('div', 'android-candidate-grid');
            grid.append(...rows.map(candidate));
            panel.replaceChildren(element('p', 'android-panel-heading', `候補一覧 (${rows.length}件)`), grid);
        } else if (picker) {
            const tabBar = element('div', 'android-picker-tabs');
            const grid = element('div', 'android-picker-grid');
            const back = button('android-cap android-action', 'あa1', () => {
                picker = '';
                renderPanel();
            }, 'キーボードに戻る');
            const tabs = pickers[picker];
            const controls = tabs.map(tab => button('android-picker-tab', tab.icon || tab.label, () => select(tab), tab.label));
            function select(tab) {
                controls.forEach((key, index) => key.setAttribute('aria-pressed', String(tabs[index] === tab)));
                grid.replaceChildren(...tab.values.map(value => button('android-picker-value', value, () => dispatch('literal', value))));
                grid.scrollTop = 0;
            }
            tabBar.append(back, ...controls);
            panel.replaceChildren(tabBar, grid);
            select(tabs[0]);
        }
    }

    function perform(action) {
        if (action === 'mode') {
            dispatch('modeChange');
            mode = modes[(modes.indexOf(mode) + 1) % modes.length];
            shifted = false;
            renderKeys();
        } else if (action === 'shift') {
            shifted = !shifted;
            renderKeys();
        } else if (action in pickers) {
            picker = action;
            renderPanel();
        } else if (action === 'space' && mode !== 'japanese') dispatch('literal', ' ');
        else dispatch(action === 'undo' ? 'backspace' : action);
    }

    function sideKey(action) {
        if (action === 'mode') {
            const modeIndex = modes.indexOf(mode);
            const key = button('android-cap android-action android-mode', '', () => perform(action), `入力モード: ${modeLabels[modeIndex]}`);
            key.append(...['あ', 'a', '1'].map((value, index) => element('span', index === modeIndex ? 'active' : '', value)));
            return key;
        }
        const [defaultLabel, description] = actionKeys[action];
        const isQwerty = keyboardLayout === 'qwerty' && mode !== 'numeric';
        const label = isQwerty && action === 'space' ? (mode === 'japanese' ? '日本語' : 'English')
            : isQwerty && action === 'enter' ? '↵' : defaultLabel;
        const repeats = ['left', 'right', 'backspace', 'undo'].includes(action);
        const key = button(`android-cap android-action${action === 'enter' ? ' android-enter' : ''}`, label, event => {
            if (!repeats || event.detail === 0) perform(action);
        }, description);
        if (action === 'shift') key.setAttribute('aria-pressed', String(shifted));
        if (repeats) {
            let timer;
            key.addEventListener('pointerdown', event => {
                if (event.button !== 0 || timer) return;
                key.setPointerCapture(event.pointerId);
                perform(action);
                let count = 0;
                function repeat() {
                    if (!key.isConnected || root.disabled || root.hidden || document.hidden) {
                        timer = undefined;
                        return;
                    }
                    perform(action);
                    timer = setTimeout(repeat, count < 6 ? 50 : count < 16 ? 35 : 22);
                    count += 1;
                }
                timer = setTimeout(repeat, action === 'left' || action === 'right' ? 300 : 350);
            });
            for (const event of ['pointerup', 'pointercancel', 'lostpointercapture']) {
                key.addEventListener(event, () => {
                    clearTimeout(timer);
                    timer = undefined;
                });
            }
        }
        return key;
    }

    function keyInput(def, direction) {
        const value = def[direction];
        if (!value) return;
        if (def.center === 'TOGGLE') dispatch('kanaModifier', modifiers[direction]);
        else if (value === 'CASE_TOGGLE') {
            shifted = !shifted;
            renderKeys();
        } else dispatch(mode === 'japanese' ? 'appendKana' : 'literal', value);
    }

    function characterKey(def, isQwerty = false) {
        function commit(direction) {
            if (!isQwerty) {
                keyInput(def, direction);
                return;
            }
            const value = def[direction];
            if (!value) return;
            if (mode === 'english') dispatch('literal', value);
            else dispatch(/^[、。？ー]$/u.test(value) ? 'appendKana' : 'append', value);
            if (shifted) {
                shifted = false;
                renderKeys();
            }
        }
        const special = def.center === 'TOGGLE' || def.center === 'CASE_TOGGLE';
        const key = button(`android-cap${special ? ' android-action' : ''}`, '', event => {
            if (event.detail === 0) commit('center');
        }, `${def.label}を入力`);
        key.append(element('span', def.label.length > 2 ? 'compact-label' : '', def.label));
        if (def.subText) key.append(element('small', '', def.subText));
        let gesture = null;
        key.addEventListener('pointerdown', event => {
            if (event.button !== 0 || gesture) return;
            key.setPointerCapture(event.pointerId);
            const keyBounds = key.getBoundingClientRect();
            const boardBounds = board.getBoundingClientRect();
            const guide = element('div', isQwerty ? 'android-qwerty-guide' : 'android-flick-guide');
            guide.setAttribute('aria-hidden', 'true');
            const center = keyBounds.left + keyBounds.width / 2 - boardBounds.left;
            if (isQwerty) {
                guide.style.left = `${Math.max(20, Math.min(boardBounds.width - 20, center))}px`;
                guide.style.top = `${keyBounds.top - boardBounds.top}px`;
                guide.textContent = def.label;
            } else {
                guide.style.left = `${Math.max(48, Math.min(boardBounds.width - 48, center))}px`;
                guide.style.top = `${Math.max(48, Math.min(boardBounds.height - 48, keyBounds.top + keyBounds.height / 2 - boardBounds.top))}px`;
                for (const direction of ['up', 'left', 'center', 'right', 'down']) {
                    const cell = element('span', direction, direction === 'center' ? def.label : def[direction]);
                    cell.hidden = !cell.textContent;
                    guide.append(cell);
                }
            }
            guide.dataset.direction = 'center';
            board.append(guide);
            gesture = { id: event.pointerId, x: event.clientX, y: event.clientY, direction: 'center', guide };
            key.dataset.pressed = '';
        });
        function follow(event) {
            if (gesture?.id !== event.pointerId) return;
            const dx = event.clientX - gesture.x;
            const dy = event.clientY - gesture.y;
            if (isQwerty) {
                const up = def.up && (gesture.direction === 'up'
                    ? dy <= -geometry.flickThreshold * geometry.returnRatio
                    : dy <= -geometry.flickThreshold && -dy >= Math.abs(dx) * 0.6);
                gesture.direction = up ? 'up' : 'center';
                gesture.guide.textContent = def[gesture.direction];
            } else gesture.direction = flickDirection(dx, dy, gesture.direction);
            gesture.guide.dataset.direction = gesture.direction;
        }
        function finish(event) {
            if (gesture?.id !== event.pointerId) return;
            const { guide, direction } = gesture;
            gesture = null;
            guide.remove();
            delete key.dataset.pressed;
            if (event.type === 'pointerup') commit(direction);
        }
        key.addEventListener('pointermove', follow);
        key.addEventListener('pointerup', event => {
            follow(event);
            finish(event);
        });
        key.addEventListener('pointercancel', finish);
        key.addEventListener('lostpointercapture', finish);
        return key;
    }

    function qwertyExtraKey() {
        const key = composing ? qwertyCharacter(mode === 'japanese' ? '？' : '?') : sideKey('emoji');
        key.classList.add('android-qwerty-extra');
        return key;
    }

    function qwertyCharacter(character, up) {
        const label = shifted ? character.toUpperCase() : character;
        return characterKey({ label, center: label, up, subText: up }, true);
    }

    function renderQwerty() {
        const numberHints = shifted ? qwerty.shiftedSymbols : qwerty.digits;
        const rows = qwerty[mode].map((letters, index) => {
            const row = element('div', `android-qwerty-row android-qwerty-row-${index + 1}`);
            const characters = letters.map((letter, column) => qwertyCharacter(letter, index === 0 ? numberHints[column] : undefined));
            if (index === 2) row.append(sideKey('shift'), ...characters, sideKey('backspace'));
            else row.append(...characters);
            return row;
        });
        const footer = element('div', 'android-qwerty-row android-qwerty-footer');
        const comma = qwertyCharacter(mode === 'japanese' ? '、' : ',');
        const extra = qwertyExtraKey();
        footer.append(sideKey('mode'), ...(mode === 'japanese' ? [comma, extra] : [extra, comma]), sideKey('space'),
            qwertyCharacter(mode === 'japanese' ? '。' : '.'), sideKey('left'), sideKey('right'), sideKey('enter'));
        board.replaceChildren(...rows, footer);
    }

    function renderKeys() {
        const isQwerty = keyboardLayout === 'qwerty' && mode !== 'numeric';
        board.dataset.layout = isQwerty ? 'qwerty' : 'flick';
        root.setAttribute('aria-label', `Android ${isQwerty ? 'QWERTY' : '12キー'}キーボード`);
        layoutToggle.hidden = mode === 'numeric' || Boolean(rows.length);
        if (isQwerty) {
            renderQwerty();
            return;
        }
        const layout = layouts[mode === 'english' && shifted ? 'shifted' : mode];
        const keyRows = layout.map((keys, index) => {
            const row = element('div', 'android-key-row');
            const [left, right] = sides[mode][index];
            row.append(sideKey(left), ...keys.map(key => characterKey(key)), sideKey(right));
            return row;
        });
        board.replaceChildren(...keyRows);
    }
    renderKeys();

    return function render(state, selection, offer, disabled) {
        root.disabled = disabled;
        const reading = state?.reading ?? '';
        if (Boolean(reading) !== composing) {
            composing = Boolean(reading);
            board.querySelector('.android-qwerty-extra')?.replaceWith(qwertyExtraKey());
        }
        const corrected = Boolean(state?.appliedCorrection);
        originalPrefix.textContent = reading ? (corrected ? '補正済み: ' : '原文: ') : '入力待ち';
        originalValue.textContent = reading;
        original.title = reading;
        original.classList.toggle('corrected', corrected);
        proposal.hidden = !offer;
        if (offer) {
            const prediction = offer.status === 'prediction';
            proposal.dataset.kind = offer.status;
            proposalTitle.textContent = prediction ? '先読み' : '補正';
            proposalValue.textContent = prediction ? offer.continuation : offer.replacement;
            proposal.setAttribute('aria-label', `${proposalTitle.textContent} ${proposalValue.textContent} を採用`);
        }
        undo.hidden = !corrected;
        const candidates = state?.candidates ?? [];
        if (rows === candidates && selected === selection) return;
        rows = candidates;
        selected = selection;
        const nextTop = `${reading}:${rows[0]?.text ?? ''}`;
        const scroll = nextTop === top ? strip.scrollLeft : 0;
        top = nextTop;
        strip.replaceChildren(...rows.map(candidate));
        strip.scrollLeft = scroll;
        settings.hidden = Boolean(rows.length);
        layoutToggle.hidden = mode === 'numeric' || Boolean(rows.length);
        expand.hidden = !rows.length;
        if (!rows.length) expanded = false;
        if (!picker) renderPanel();
    };
}
