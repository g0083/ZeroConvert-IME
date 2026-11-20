export type DictionaryPos = "proper_noun" | "common_noun" | "sahen_noun" | "shortcut" | "symbol";
export type DictionaryEntry = { reading: string; word: string; pos: DictionaryPos };
export declare const DICTIONARY_POS: Readonly<Record<DictionaryPos, string>>;
export type DictionaryRowErrors = Partial<Record<keyof DictionaryEntry, string>>;
export declare const MAX_ENTRIES: number;
export declare const normalizeDictionaryEntries: (value: unknown) => DictionaryEntry[];
export declare const validateDictionaryEntries: (draft: DictionaryEntry[]) => {
    entries: DictionaryEntry[];
    errors: DictionaryRowErrors[];
};
export declare const isDictionaryDirty: (
    draft: DictionaryEntry[],
    persisted: DictionaryEntry[] | null,
) => boolean;
