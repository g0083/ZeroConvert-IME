import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { searchSettings, settingsIndex } from "../src/lib/settings-index.ts";

const srcDir = fileURLToPath(new URL("../src/", import.meta.url));

// 操作できる行（htmlFor を持つ SettingsRow）だけを検索対象の必須集合とする。
// About のモデル・ライセンス行のような読み取り専用の情報行は索引に含めない。
function operableRowLabels(relativePath) {
    const source = readFileSync(path.join(srcDir, relativePath), "utf8");
    return [...source.matchAll(/<SettingsRow\s+([^>]*?)>/g)]
        .map((match) => match[1])
        .filter((attributes) => attributes.includes("htmlFor"))
        .map((attributes) => attributes.match(/label="([^"]+)"/)?.[1])
        .filter(Boolean);
}

test("index covers every operable setting row", () => {
    const indexed = new Set(settingsIndex.map((entry) => entry.label));
    const missing = ["pages/general.tsx", "pages/zenzai.tsx", "pages/about.tsx"]
        .flatMap(operableRowLabels)
        .filter((label) => !indexed.has(label));
    assert.deepEqual(missing, []);
});

test("search prefers label prefix matches and is width insensitive", () => {
    assert.equal(searchSettings("ばっくえんど")[0]?.label, "バックエンド");
    assert.equal(searchSettings("ＧＰＵ")[0]?.label, "GPUに載せる層数");
});

test("search matches keywords and section names", () => {
    assert.ok(searchSettings("クリップボード").some((entry) => entry.url === "/keys"));
    assert.ok(searchSettings("るび").some((entry) => entry.label === "ライブ変換中の読みを表示"));
});

test("search returns nothing for blank or unmatched queries", () => {
    assert.deepEqual(searchSettings("   "), []);
    assert.deepEqual(searchSettings("存在しない設定名"), []);
});

test("index entries point at existing routes and have unique labels per page", () => {
    const routes = new Set(["/", "/conversion", "/keys", "/dictionary", "/benchmark", "/appearance", "/advanced", "/about"]);
    for (const entry of settingsIndex) assert.ok(routes.has(entry.url), entry.url);
    const keys = settingsIndex.map((entry) => `${entry.url}|${entry.section}|${entry.label}`);
    assert.equal(new Set(keys).size, keys.length);
});
