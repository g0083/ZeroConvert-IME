// Settings shared by the Web page and its tests.
//
// Defaults come from the staged `ime-settings.json`, which is generated from the
// Windows deployment settings, so the browser starts from the same values the
// Windows build ships. Only the settings the Web page can honour are exposed;
// everything else stays with the conversion engine.

export const SETTINGS_STORE_KEY = 'zerocon-settings-v1';

const BOOLEAN_KEYS = ['correction', 'prediction'];
const LEARNING_MODES = ['enabled', 'read_only', 'disabled'];

/// Reads the effective settings: staged defaults, then any stored override.
///
/// An unknown or malformed stored value is ignored rather than disabling a
/// feature the user never touched, so a partial write cannot silently change
/// behaviour.
export function readSettings(storage, defaults) {
    const settings = { ...defaults };
    let stored = null;
    try {
        stored = JSON.parse(storage.getItem(SETTINGS_STORE_KEY) ?? 'null');
    } catch {
        stored = null;
    }
    if (stored && typeof stored === 'object') {
        for (const key of BOOLEAN_KEYS) {
            if (typeof stored[key] === 'boolean') settings[key] = stored[key];
        }
        if (LEARNING_MODES.includes(stored.learning)) settings.learning = stored.learning;
    }
    return settings;
}

/// Stores only the overridable settings, so a future default change still flows
/// through for values the user never chose.
export function writeSettings(storage, settings) {
    const overrides = {};
    for (const key of BOOLEAN_KEYS) {
        if (typeof settings[key] === 'boolean') overrides[key] = settings[key];
    }
    if (LEARNING_MODES.includes(settings.learning)) overrides.learning = settings.learning;
    try {
        storage.setItem(SETTINGS_STORE_KEY, JSON.stringify(overrides));
        return true;
    } catch {
        return false;
    }
}

/// Maps the staged `ime-settings.json` to the values the page needs.
export function stagedSettings(document) {
    const general = document?.general ?? {};
    return {
        correction: general.experimental_typo_correction !== false,
        prediction: general.experimental_ai_assist !== false,
        autoCommit: general.auto_commit_live_conversion === true,
        showReading: general.show_live_conversion_reading !== false,
        showCandidatesAfterSpace: general.show_candidate_window_after_space === true,
        learning: document?.learning?.mode === 'none' ? 'disabled' : document?.learning?.mode ?? 'enabled',
    };
}
