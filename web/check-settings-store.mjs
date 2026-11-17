// Verifies the Web settings store: staged Windows values become the defaults,
// a stored override applies, a malformed override falls back instead of
// disabling a feature, and only overridable keys are persisted.
import assert from 'node:assert/strict';
import {
    SETTINGS_STORE_KEY,
    readSettings,
    stagedSettings,
    writeSettings,
} from './pages/settings-store.mjs';

function storage(initial = {}) {
    const values = new Map(Object.entries(initial));
    return {
        getItem: key => values.get(key) ?? null,
        setItem: (key, value) => values.set(key, value),
    };
}

const staged = {
    general: {
        experimental_typo_correction: true,
        experimental_ai_assist: false,
        auto_commit_live_conversion: true,
    },
    learning: { mode: 'read_only' },
};
const defaults = stagedSettings(staged);
assert.deepEqual(defaults, {
    correction: true,
    prediction: false,
    autoCommit: true,
    showReading: true,
    showCandidatesAfterSpace: false,
    learning: 'read_only',
});

// No override: the staged values apply.
assert.deepEqual(readSettings(storage(), defaults), defaults);

// A stored override replaces only the keys it names.
assert.deepEqual(
    readSettings(storage({ [SETTINGS_STORE_KEY]: '{"prediction":true}' }), defaults),
    { ...defaults, prediction: true },
);

// A malformed or non-boolean override is ignored, not treated as false.
assert.deepEqual(readSettings(storage({ [SETTINGS_STORE_KEY]: '{' }), defaults), defaults);
assert.deepEqual(
    readSettings(storage({ [SETTINGS_STORE_KEY]: '{"correction":"yes"}' }), defaults),
    defaults,
);

// Only the overridable keys are persisted, so unrelated values stay with the
// engine defaults.
const disk = storage();
assert.equal(writeSettings(disk, { ...defaults, prediction: true, learning: 'disabled' }), true);
assert.deepEqual(JSON.parse(disk.getItem(SETTINGS_STORE_KEY)), {
    correction: true,
    prediction: true,
    learning: 'disabled',
});
assert.equal(readSettings(disk, defaults).learning, 'disabled');

// A missing settings document still yields usable defaults.
assert.deepEqual(stagedSettings(null), {
    correction: true,
    prediction: true,
    autoCommit: false,
    showReading: true,
    showCandidatesAfterSpace: false,
    learning: 'enabled',
});
assert.equal(stagedSettings({ learning: { mode: 'none' } }).learning, 'disabled');

process.stdout.write('Web settings follow the staged Windows values.\n');
