import posCatalog from "../../../../data/user-dictionary-pos.json" with { type: "json" };
import { DICTIONARY_POS, MAX_ENTRIES, dictionaryEntryKey } from "./dictionary-entries.js";

/** 書き出しファイルの識別子。読み込み時にこの形式かどうかを確かめる。 */
export const DICTIONARY_FILE_FORMAT = "zeroconvert-user-dictionary";
export const DICTIONARY_FILE_VERSION = 1;

const POS_BY_ALIAS = new Map();
for (const [key, { label, mozc, msime, aliases }] of Object.entries(posCatalog)) {
    for (const alias of [key, label, mozc, msime, ...aliases]) {
        // Microsoft IME uses 固有名詞 for both proper nouns and organizations.
        if (!POS_BY_ALIAS.has(alias)) POS_BY_ALIAS.set(alias, key);
    }
}
const SKIP_POS = new Set(["サジェストのみ", "抑制単語", "制御単語"]);

const entryKey = ({ reading, word }) => dictionaryEntryKey(reading, word);

const cleanEntry = (value) => {
    if (!value || typeof value !== "object") return null;
    const reading = typeof value.reading === "string" ? value.reading.trim() : "";
    const word = typeof value.word === "string" ? value.word.trim() : "";
    if (!reading || !word) return null;
    return { reading, word };
};

const collectEntries = (values, defaultPos) => {
    const entries = [];
    let skipped = 0;
    let mapped = 0;
    for (const value of values) {
        const entry = cleanEntry(value);
        const alias = typeof value?.pos === "string"
            ? value.pos.normalize("NFKC").split(":", 1)[0].trim().replace(/[$*]$/, "") : "";
        if (!entry || SKIP_POS.has(alias)) {
            skipped += 1;
            continue;
        }
        const pos = alias ? POS_BY_ALIAS.get(alias) : defaultPos;
        if (!pos) mapped += 1;
        entries.push({ ...entry, pos: pos ?? "common_noun" });
    }
    return { entries, skipped, mapped };
};

const parseJsonEntries = (text) => {
    let data;
    try {
        data = JSON.parse(text);
    } catch {
        throw new Error("JSON として読み込めませんでした。");
    }
    if (data?.format === DICTIONARY_FILE_FORMAT && data.version !== DICTIONARY_FILE_VERSION) {
        throw new Error("このバージョンの辞書ファイルは読み込めません。");
    }
    const values = Array.isArray(data)
        ? data
        : Array.isArray(data?.entries)
          ? data.entries
          : Array.isArray(data?.user_dictionary?.entries)
            ? data.user_dictionary.entries
            : null;
    if (!values) throw new Error("辞書データが見つかりませんでした（entries がありません）。");
    return collectEntries(values, "proper_noun");
};

const parseTsvEntries = (text) => {
    const values = [];
    let skipped = 0;
    const lines = text.split(/\r\n|\r|\n/);
    const header = lines.find((line) => line.trim())?.trim().toLowerCase() ?? "";
    const gboard = header === "# gboard dictionary version:1";
    const commentPrefix = /^!microsoft ime|^!!(?:atok_tango_text_header|dicut)/.test(header) ? "!" : "#";
    for (const line of lines) {
        if (!line.trim() || line.startsWith(commentPrefix)
            || (/^[#!]/.test(line.trimStart()) && !line.includes("\t"))) continue;
        const columns = line.split("\t");
        if (columns.length >= 2) {
            values.push({ reading: columns[0], word: columns[1], pos: gboard ? "shortcut" : columns[2] });
            continue;
        }
        // 貼り付け用に、タブが無い行は「読み 単語」の空白区切りとして扱う。
        const spaced = line.trim().match(/^(\S+)[\u3000 ]+(.+)$/);
        if (!spaced) {
            skipped += 1;
            continue;
        }
        values.push({ reading: spaced[1], word: spaced[2] });
    }
    const collected = collectEntries(values, "common_noun");
    return { ...collected, skipped: collected.skipped + skipped };
};

/**
 * JSON（このアプリの書き出し形式・entries 配列・settings.json）または TSV を読み取る。
 * 読めなかった行は `skipped` に数え、例外はファイル全体が辞書として解釈できない場合だけ投げる。
 */
export const parseDictionaryFile = (text) => {
    const content = String(text ?? "").replace(/^\uFEFF/, "");
    const trimmed = content.trim();
    if (!trimmed) throw new Error("ファイルが空です。");
    if (/^[[{]/.test(trimmed)) return parseJsonEntries(trimmed);
    const parsed = parseTsvEntries(content);
    if (parsed.entries.length === 0) {
        throw new Error("取り込める「読み」と「単語」の行が見つかりませんでした。");
    }
    return parsed;
};

/** テキスト形式では、区切り文字を含む行とコメントとして扱われる行を除く。 */
export const exportableDictionaryEntries = (entries, format = "json") =>
    entries.filter((entry) => cleanEntry(entry) && Object.hasOwn(DICTIONARY_POS, entry.pos) && (
        format === "json" || (!/[\t\r\n\u0000]/.test(entry.reading + entry.word)
            && !entry.reading.startsWith(format === "msime" ? "!" : "#"))
    ));

export const serializeDictionaryJson = (entries, exportedAt = new Date().toISOString()) =>
    `${JSON.stringify(
        {
            format: DICTIONARY_FILE_FORMAT,
            version: DICTIONARY_FILE_VERSION,
            exported_at: exportedAt,
            entry_count: entries.length,
            entries: entries.map(({ reading, word, pos }) => ({
                reading,
                word,
                pos: pos ?? "proper_noun",
            })),
        },
        null,
        2,
    )}\n`;

export const serializeDictionaryTsv = (entries, format = "tsv") => {
    const msime = format === "msime";
    const lines = msime ? ["!Microsoft IME Dictionary Tool", "!Version:", "!Format:WORDLIST"] : [];
    for (const { reading, word, pos } of entries) {
        const preset = posCatalog[pos];
        const label = msime ? preset.msime : preset.mozc;
        lines.push(`${reading}\t${word}\t${label}\t`);
    }
    return lines.length ? lines.join(msime ? "\r\n" : "\n") + (msime ? "\r\n" : "\n") : "";
};

/** 保存ダイアログの初期ファイル名。 */
export const dictionaryFileName = (format, date = new Date()) => {
    const part = (value, width) => String(value).padStart(width, "0");
    const stamp = `${part(date.getFullYear(), 4)}${part(date.getMonth() + 1, 2)}${part(date.getDate(), 2)}`;
    return `zeroconvert-user-dictionary-${stamp}.${format === "json" ? "json" : "txt"}`;
};

// 重複判定は保存時と同じく前後の空白を除いた値で行う。未入力の行はそのまま残す。
const keysOf = (entries) => {
    const seen = new Set();
    for (const entry of entries) seen.add(entryKey(cleanEntry(entry) ?? entry));
    return seen;
};

/** 現在の辞書へ追加する。重複と上限超過は件数だけを返す。 */
export const mergeDictionaryEntries = (current, incoming) => {
    const entries = [...current];
    const seen = keysOf(entries);
    let added = 0;
    let duplicates = 0;
    let overflow = 0;
    for (const value of incoming) {
        const fields = cleanEntry(value);
        const entry = fields && { ...fields, pos: value.pos ?? "proper_noun" };
        if (!entry) continue;
        const key = entryKey(entry);
        if (seen.has(key)) {
            duplicates += 1;
            continue;
        }
        if (entries.length >= MAX_ENTRIES) {
            overflow += 1;
            continue;
        }
        seen.add(key);
        entries.push(entry);
        added += 1;
    }
    return { entries, added, duplicates, overflow };
};

/** 現在の辞書を読み込んだ内容で置き換える。 */
export const replaceDictionaryEntries = (incoming) => {
    const entries = [];
    const seen = new Set();
    let duplicates = 0;
    let overflow = 0;
    for (const value of incoming) {
        const fields = cleanEntry(value);
        const entry = fields && { ...fields, pos: value.pos ?? "proper_noun" };
        if (!entry) continue;
        const key = entryKey(entry);
        if (seen.has(key)) {
            duplicates += 1;
            continue;
        }
        if (entries.length >= MAX_ENTRIES) {
            overflow += 1;
            continue;
        }
        seen.add(key);
        entries.push(entry);
    }
    return { entries, duplicates, overflow };
};
