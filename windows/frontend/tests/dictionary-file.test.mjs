import test from "node:test";
import assert from "node:assert/strict";
import { DICTIONARY_POS, MAX_ENTRIES } from "../src/lib/dictionary-entries.js";
import {
    DICTIONARY_FILE_FORMAT,
    DICTIONARY_FILE_VERSION,
    dictionaryFileName,
    exportableDictionaryEntries,
    mergeDictionaryEntries,
    parseDictionaryFile,
    replaceDictionaryEntries,
    serializeDictionaryJson,
    serializeDictionaryTsv,
} from "../src/lib/dictionary-file.js";

const entry = (reading = "よみ", word = "単語", pos = "proper_noun") => ({ reading, word, pos });

test("JSON export round-trips every field and keeps the file identifiable", () => {
    const entries = Object.keys(DICTIONARY_POS).map((pos, index) => entry(`よみ${index}`, "表記", pos));
    const json = serializeDictionaryJson(entries, "2026-02-14T00:00:00.000Z");
    const parsed = JSON.parse(json);
    assert.equal(parsed.format, DICTIONARY_FILE_FORMAT);
    assert.equal(parsed.version, DICTIONARY_FILE_VERSION);
    assert.equal(parsed.exported_at, "2026-02-14T00:00:00.000Z");
    assert.equal(parsed.entry_count, entries.length);
    assert.deepEqual(parseDictionaryFile(json), { entries, skipped: 0, mapped: 0 });
});

test("JSON import accepts a bare array, an entries object, and settings.json", () => {
    const expected = { entries: [entry()], skipped: 0, mapped: 0 };
    assert.deepEqual(parseDictionaryFile(JSON.stringify([entry()])), expected);
    assert.deepEqual(parseDictionaryFile(JSON.stringify({ entries: [entry()] })), expected);
    assert.deepEqual(
        parseDictionaryFile(JSON.stringify({ user_dictionary: { entries: [entry()] } })),
        expected,
    );
});

test("JSON import trims values, defaults unknown POS, and counts rows it cannot use", () => {
    const text = JSON.stringify({
        entries: [
            { reading: " よみ ", word: " 単語 ", pos: "固有名詞" },
            { reading: "ことば", word: "言葉", pos: "unknown" },
            { reading: "", word: "空" },
            { reading: 1, word: "数値" },
            null,
        ],
    });
    assert.deepEqual(parseDictionaryFile(text), {
        entries: [entry(), entry("ことば", "言葉", "common_noun")],
        skipped: 3,
        mapped: 1,
    });
});

test("TSV import reads three columns, Japanese POS labels, comments, and blank lines", () => {
    const text = [
        "# 読み	単語	品詞",
        "ぜろこん	ZeroCon	固有名詞",
        "めーる	example@example.com	定型文・ショートカット",
        "",
        "かお	(^o^)	記号・顔文字",
        "とりあえず	仮	不明な品詞",
        "よみだけ",
    ].join("\r\n");
    assert.deepEqual(parseDictionaryFile(text), {
        entries: [
            entry("ぜろこん", "ZeroCon"),
            entry("めーる", "example@example.com", "shortcut"),
            entry("かお", "(^o^)", "symbol"),
            entry("とりあえず", "仮", "common_noun"),
        ],
        skipped: 1,
        mapped: 1,
    });
});

test("TSV export round-trips and pasted space-separated lines still import", () => {
    const entries = [entry(), entry("かお", "(^o^)", "symbol")];
    assert.deepEqual(parseDictionaryFile(serializeDictionaryTsv(entries)), { entries, skipped: 0, mapped: 0 });
    assert.deepEqual(parseDictionaryFile("ぜろこん　ZeroCon"), {
        entries: [entry("ぜろこん", "ZeroCon", "common_noun")], skipped: 0, mapped: 0,
    });
});

test("Google and Microsoft text use their respective POS names and Microsoft uses WORDLIST", () => {
    const entries = [
        entry("へんかん", "変換", "sahen_noun"), entry("そしき", "団体", "organization"),
        entry("みょうじ", "田中", "family_name"), entry("かお", "(^o^)", "symbol"),
    ];
    const google = serializeDictionaryTsv(entries);
    assert.match(google, /へんかん\t変換\t名詞サ変\t\n/);
    assert.deepEqual(parseDictionaryFile(google), { entries, skipped: 0, mapped: 0 });
    const microsoft = serializeDictionaryTsv(entries, "msime");
    assert.match(microsoft, /^!Microsoft IME Dictionary Tool\r\n!Version:\r\n!Format:WORDLIST\r\n/);
    assert.match(microsoft, /へんかん\t変換\tさ変名詞\t\r\n/);
    assert.deepEqual(parseDictionaryFile(microsoft).entries, entries.map((row) =>
        row.pos === "organization" ? { ...row, pos: "proper_noun" } : row));
});

test("ATOK labels and markers load, but suggestion-only and suppression entries are skipped", () => {
    const parsed = parseDictionaryFile(
        "!!ATOK_TANGO_TEXT_HEADER_1\r\nたなか\t田中\t固有人姓*\r\nちめい\t地名\t固有地名$\r\n" +
        "よみ\t無視\t抑制単語\r\nよみ\t補完\tサジェストのみ\r\n");
    assert.deepEqual(parsed, {
        entries: [entry("たなか", "田中", "family_name"), entry("ちめい", "地名", "place_name")], skipped: 2, mapped: 0,
    });
});

test("merge preserves the existing POS when kana spelling differs and JSON preserves TSV-incompatible rows", () => {
    assert.equal(mergeDictionaryEntries([entry("が")], [entry("ガ", "単語", "family_name")]).duplicates, 1);
    const rows = [entry("#はっしゅ", "ハッシュ"), entry("よみ", "一行\n二行")];
    assert.deepEqual(exportableDictionaryEntries(rows, "json"), rows);
    assert.deepEqual(exportableDictionaryEntries(rows, "tsv"), []);
    assert.deepEqual(parseDictionaryFile(serializeDictionaryJson(rows)).entries, rows);
});

test("files that are not a dictionary fail with a message instead of importing nothing", () => {
    assert.throws(() => parseDictionaryFile("   "), /空です/);
    assert.throws(() => parseDictionaryFile("{\"entries\": [}"), /JSON/);
    assert.throws(() => parseDictionaryFile(JSON.stringify({ format: "other" })), /辞書データ/);
    assert.throws(() => parseDictionaryFile("読みだけの行"), /見つかりませんでした/);
});

test("export skips incomplete rows so a draft with an empty cell never writes them out", () => {
    assert.deepEqual(
        exportableDictionaryEntries([entry(), entry("", "単語"), entry("よみ", " "), entry(" ", "")]),
        [entry()],
    );
});

test("merging keeps existing rows, skips duplicates, and stops at the limit", () => {
    assert.deepEqual(mergeDictionaryEntries([entry()], [entry(), entry("べつ", "別")]), {
        entries: [entry(), entry("べつ", "別")],
        added: 1,
        duplicates: 1,
        overflow: 0,
    });
    const full = Array.from({ length: MAX_ENTRIES }, (_, index) => entry(`よみ${index}`));
    const result = mergeDictionaryEntries(full, [entry("あたらしい", "新"), entry("さらに", "更")]);
    assert.equal(result.entries.length, MAX_ENTRIES);
    assert.equal(result.added, 0);
    assert.equal(result.overflow, 2);
    assert.equal(full.length, MAX_ENTRIES, "the current dictionary must not be mutated");
});

test("replacing dedupes the file and caps it without keeping the current rows", () => {
    assert.deepEqual(replaceDictionaryEntries([entry(), entry(" よみ ", "単語"), entry("べつ", "別")]), {
        entries: [entry(), entry("べつ", "別")],
        duplicates: 1,
        overflow: 0,
    });
    const many = Array.from({ length: MAX_ENTRIES + 2 }, (_, index) => entry(`よみ${index}`));
    const result = replaceDictionaryEntries(many);
    assert.equal(result.entries.length, MAX_ENTRIES);
    assert.equal(result.overflow, 2);
});

test("the default file name is dated and follows the export format", () => {
    const date = new Date(2026, 1, 4);
    assert.equal(dictionaryFileName("json", date), "zeroconvert-user-dictionary-20260204.json");
    assert.equal(dictionaryFileName("tsv", date), "zeroconvert-user-dictionary-20260204.txt");
    assert.equal(dictionaryFileName("msime", date), "zeroconvert-user-dictionary-20260204.txt");
});
