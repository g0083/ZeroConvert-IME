import type { DictionaryEntry, DictionaryPos } from "./dictionary-entries.js";

export type DictionaryExportFormat = "json" | "tsv" | "msime";

export type ParsedDictionaryFile = { entries: DictionaryEntry[]; skipped: number; mapped: number };

export type DictionaryMergeResult = {
    entries: DictionaryEntry[];
    added: number;
    duplicates: number;
    overflow: number;
};

export type DictionaryReplaceResult = {
    entries: DictionaryEntry[];
    duplicates: number;
    overflow: number;
};

export declare const DICTIONARY_FILE_FORMAT: string;
export declare const DICTIONARY_FILE_VERSION: number;
export declare const parseDictionaryFile: (text: string) => ParsedDictionaryFile;
export declare const exportableDictionaryEntries: (entries: DictionaryEntry[], format?: DictionaryExportFormat) => DictionaryEntry[];
export declare const serializeDictionaryJson: (entries: DictionaryEntry[], exportedAt?: string) => string;
export declare const serializeDictionaryTsv: (entries: DictionaryEntry[], format?: "tsv" | "msime") => string;
export declare const dictionaryFileName: (format: DictionaryExportFormat, date?: Date) => string;
export declare const mergeDictionaryEntries: (current: DictionaryEntry[], incoming: DictionaryEntry[]) => DictionaryMergeResult;
export declare const replaceDictionaryEntries: (incoming: DictionaryEntry[]) => DictionaryReplaceResult;

export type { DictionaryEntry, DictionaryPos };
