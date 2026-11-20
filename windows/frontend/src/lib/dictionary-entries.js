export const MAX_ENTRIES = 10_000;

export const DICTIONARY_POS = {
    proper_noun: "固有名詞",
    common_noun: "一般名詞",
    sahen_noun: "サ変名詞",
    shortcut: "定型文・ショートカット",
    symbol: "記号・顔文字",
};

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
        const key = JSON.stringify([reading, word]);
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
