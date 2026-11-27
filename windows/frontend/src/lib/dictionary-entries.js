import posCatalog from "../../../../data/user-dictionary-pos.json" with { type: "json" };

export const MAX_ENTRIES = 10_000;

export const DICTIONARY_POS = Object.fromEntries(Object.entries(posCatalog).map(([key, { label }]) => [key, label]));
export const dictionaryEntryKey = (reading, word) => JSON.stringify([
    reading.trim().normalize("NFC").replace(/[ァ-ヶヽヾヿ]/g, (kana) => String.fromCharCode(kana.charCodeAt(0) - 96)),
    word.trim().normalize("NFC"),
]);

export const normalizeDictionaryEntries = (value) =>
    Array.isArray(value)
        ? value
              .filter((entry) => entry && typeof entry.reading === "string" && typeof entry.word === "string")
              .map(({ reading, word, pos }) => ({ reading, word, pos: pos ?? "proper_noun" }))
        : [];

export const validateDictionaryEntries = (draft) => {
    const entries = draft.map(({ reading, word, pos }) => ({
        reading: reading.trim(),
        word: word.trim(),
        pos: pos ?? "proper_noun",
    }));
    const seen = new Set();
    const errors = entries.map(({ reading, word, pos }, index) => {
        const error = {};
        if (!reading) error.reading = "読みを入力してください";
        if (!word) error.word = "単語を入力してください";
        if (!Object.hasOwn(DICTIONARY_POS, pos)) error.pos = "品詞を選んでください";
        const key = dictionaryEntryKey(reading, word);
        if (reading && word && seen.has(key)) {
            error.reading = error.word = "同じ読み・単語の組み合わせが重複しています";
        }
        seen.add(key);
        if (index >= MAX_ENTRIES) error.reading = `最大 ${MAX_ENTRIES} 件までです。行を削除してください`;
        return error;
    });
    return { entries, errors };
};

export const isDictionaryDirty = (draft, persisted) =>
    persisted !== null &&
    (draft.length !== persisted.length ||
        draft.some((entry, index) =>
            entry.reading !== persisted[index].reading || entry.word !== persisted[index].word ||
            entry.pos !== persisted[index].pos));
