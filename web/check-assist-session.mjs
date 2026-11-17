// Verifies correction/prediction arbitration with fixed answers:
// normal candidates stay protected, corrections outrank predictions, a rejected
// prediction never applies, and a late answer cannot rewrite the composition.
import assert from 'node:assert/strict';
import { assistLine, mergeAssist, planAssist } from './pages/assist-session.mjs';

// Mirrors the shared Rust assist policy for the cases below.
const live = {
    assist({ op, correctionEnabled, predictionEnabled, applied, choices, displayed, id, explicit }) {
        if (op === 'ids') return { typoCorrection: 999901, predictive: 999902, translation: 999904 };
        if (op === 'plan') {
            return { correction: Boolean(correctionEnabled) && !applied, prediction: Boolean(predictionEnabled) };
        }
        if (op === 'choose') {
            const rank = value => (value === 999901 ? 2 : value === 999902 ? 1 : 0);
            const best = choices.reduce((a, b) => (rank(b[1]) > rank(a[1]) ? b : a));
            return { selected: displayed !== null && rank(displayed) >= rank(best[1]) ? displayed : best[1] };
        }
        if (op === 'adopt') {
            if (id === 999901 && (explicit)) return { action: 'replaceComposition' };
            if (id === 999902 && explicit) return { action: 'commitText' };
            return { action: 'ignore' };
        }
        return {};
    },
};

const settings = { correction: true, prediction: true };
const correction = answers => ({ id: 999901, text: 'しました', relevance: 100, autoApply: true, ...answers });
const prediction = answers => ({ id: 999902, text: '明日の会議です。', relevance: 60, ...answers });

// Request shape: the plan suppresses a second correction once one is applied.
assert.deepEqual(planAssist(live, settings, null, 'しますた'), { correction: true, prediction: true });
assert.deepEqual(planAssist(live, settings, correction({}), 'しますた'), { correction: false, prediction: true });
assert.deepEqual(planAssist(live, { correction: false, prediction: true }, null, 'しますた'), { correction: false, prediction: true });

// Normal conversion keeps the composition when the assist answer is identical.
assert.equal(mergeAssist(live, 3, 3, [prediction({ text: '明日の会議' })], null, '明日の会議'), null);

// A correction outranks a prediction even when prediction scored higher.
let merged = mergeAssist(live, 3, 3, [prediction({ relevance: 90 }), correction({})], null, 'しますた');
assert.equal(merged.id, 999901);

// A prediction alone is a legitimate assist answer to display.
merged = mergeAssist(live, 3, 3, [prediction({})], null, 'しますた');
assert.equal(merged.id, 999902);

// Merging is presentational: it never carries an adoption decision, because
// committing an assist candidate is a separate explicit step.
assert.equal(merged.action, undefined);

// No answers, or an adoption the policy rejects, yields nothing.
assert.equal(mergeAssist(live, 3, 3, [null, undefined], null, 'しますた'), null);
assert.equal(mergeAssist(live, 3, 3, [{ id: 999903, text: 'x' }], null, 'しますた'), null);

// A late answer from an older edit generation is discarded instead of applying.
assert.equal(mergeAssist(live, 4, 3, [correction({})], null, 'しますた'), null);
// Once the normal candidate already carries the correction, no assist is added.
assert.equal(mergeAssist(live, 5, 5, [correction({ text: 'しました' })], null, 'しました'), null);
assert.equal(mergeAssist(live, 5, 5, [correction({ text: 'しました' }), prediction({})], null, 'しました').id, 999902);

// The native reading envelope diffs the ordinary surface and shows just the
// prediction continuation for the current session.
const ids = { typoCorrection: 999901, predictive: 999902 };
const correctionMessage = {
    type: 'assist',
    session: 2,
    reading: 'しますた',
    displayedAssist: 999901,
    answer: { id: 999901, text: 'しました', tokenReading: 'しますた' },
    state: { candidates: [{ text: 'します', suffix: 'た' }] },
};
assert.deepEqual(assistLine(correctionMessage, ids, 2, 'しますた'), {
    status: 'proposal',
    replacement: 'しました',
    source: 'しますた',
});
assert.equal(assistLine(correctionMessage, ids, 3, 'しますた'), null);
assert.equal(assistLine(correctionMessage, ids, 2, 'しました'), null);
assert.deepEqual(
    assistLine({ ...correctionMessage, reading: 'あしたのかいぎ', displayedAssist: 999902,
        answer: { id: 999902, text: '明日の会議です。', continuation: 'です。' } }, ids, 2, 'あしたのかいぎ'),
    { status: 'prediction', replacement: '明日の会議です。', continuation: 'です。' },
);
assert.equal(assistLine({ ...correctionMessage, displayedAssist: null }, ids, 2, 'しますた'), null);

process.stdout.write('Correction and prediction arbitration matches the shared policy.\n');
