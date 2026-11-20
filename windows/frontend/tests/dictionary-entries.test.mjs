import test from "node:test";
import assert from "node:assert/strict";
import {
    DICTIONARY_POS,
    MAX_ENTRIES,
    normalizeDictionaryEntries,
    validateDictionaryEntries,
    isDictionaryDirty,
} from "../src/lib/dictionary-entries.js";

const entry = (reading = "よみ", word = "単語", pos = "proper_noun") => ({ reading, word, pos });

test("validation trims both fields and identifies the first offending field", () => {
    const draft = [entry(" よみ ", " 単語 "), entry(" \t", "語"), entry("ことば", "　")];
    const { entries, errors } = validateDictionaryEntries(draft);
    assert.deepEqual(entries[0], entry());
    assert.equal(errors.findIndex((error) => error.reading || error.word), 1);
    assert.ok(errors[1].reading);
    assert.equal(errors[1].word, undefined);
    assert.ok(errors[2].word);
    assert.equal(errors[2].reading, undefined);
    assert.equal(draft[0].reading, " よみ ", "validation must not mutate the draft");
});

test("duplicates are checked after trimming, but different words and tuple boundaries are valid", () => {
    const { errors } = validateDictionaryEntries([
        entry(), entry(" よみ ", "単語　"), entry("よみ", "別の単語"),
        entry("a\u0000b", "c"), entry("a", "b\u0000c"),
    ]);
    assert.deepEqual(errors[0], {});
    assert.ok(errors[1].reading);
    assert.ok(errors[1].word);
    assert.deepEqual(errors.slice(2), [{}, {}, {}]);
});

test("10,000 entries and an empty dictionary are valid; the first excess row has an inline error", () => {
    assert.equal(MAX_ENTRIES, 10_000);
    const draft = Array.from({ length: MAX_ENTRIES }, (_, index) => entry(`よみ${index}`));
    assert.ok(validateDictionaryEntries(draft).errors.every((error) => !error.reading && !error.word));
    const { errors } = validateDictionaryEntries([...draft, entry("追加")]);
    assert.equal(errors.findIndex((error) => error.reading || error.word), MAX_ENTRIES);
    assert.match(errors[MAX_ENTRIES].reading, /10000/);
    assert.deepEqual(validateDictionaryEntries([]), { entries: [], errors: [] });
});

test("dirty comparison tracks raw drafts, additions, deletions, reordering, and reversions", () => {
    const persisted = [entry(), entry("べつ", "別")];
    assert.equal(isDictionaryDirty([], null), false, "a failed or pending load is not an editable draft");
    assert.equal(isDictionaryDirty(structuredClone(persisted), persisted), false);
    assert.equal(isDictionaryDirty([entry(" よみ "), persisted[1]], persisted), true);
    assert.equal(isDictionaryDirty([entry("よみ", "変更"), persisted[1]], persisted), true);
    assert.equal(isDictionaryDirty([...persisted, entry("", "")], persisted), true);
    assert.equal(isDictionaryDirty(persisted.slice(1), persisted), true);
    assert.equal(isDictionaryDirty([...persisted].reverse(), persisted), true);
    assert.equal(isDictionaryDirty(persisted, persisted), false, "reverting clears dirty state");
    assert.equal(isDictionaryDirty([], []), false);
});

test("a draft stays dirty against the old snapshot until the trimmed saved entries become the baseline", () => {
    const persisted = [entry()];
    const draft = [entry(" ついか ", " 追加 ")];
    const validated = validateDictionaryEntries(draft).entries;
    assert.equal(isDictionaryDirty(draft, persisted), true);
    assert.equal(isDictionaryDirty(validated, persisted), true);
    assert.deepEqual(persisted, [entry()], "validation cannot advance the successful snapshot");
    assert.equal(isDictionaryDirty(validated, structuredClone(validated)), false);
});

test("load normalization accepts missing dictionaries and filters malformed rows without trimming drafts", () => {
    assert.deepEqual(normalizeDictionaryEntries(undefined), []);
    assert.deepEqual(normalizeDictionaryEntries({ entries: [] }), []);
    assert.deepEqual(normalizeDictionaryEntries([null, {}, { reading: 1, word: "x" }, entry(" よみ ")]), [entry(" よみ ")]);
});


test("legacy entries default to proper noun and all five POS presets round-trip", () => {
    assert.deepEqual(normalizeDictionaryEntries([{ reading: "よみ", word: "単語" }]), [entry()]);
    const presets = ["proper_noun", "common_noun", "sahen_noun", "shortcut", "symbol"];
    assert.deepEqual(Object.keys(DICTIONARY_POS), presets);
    const draft = presets.map((pos, index) => entry(`よみ${index}`, "別", pos));
    assert.deepEqual(validateDictionaryEntries(draft), { entries: draft, errors: presets.map(() => ({})) });
    assert.ok(validateDictionaryEntries([entry("よみ", "単語", "unknown")]).errors[0].pos);
    assert.equal(isDictionaryDirty([entry("よみ", "単語", "symbol")], [entry()]), true);
});
