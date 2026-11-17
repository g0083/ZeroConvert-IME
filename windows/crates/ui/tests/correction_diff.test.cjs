const test = require("node:test");
const assert = require("node:assert/strict");

const { correctionDiff, extractFocusedHunk } = require("../src/correction_diff.js");

test("short typo correction preserves short context and highlights changed span", () => {
    const diff = correctionDiff("おkせり", "おかえり");
    assert.equal(diff.original, "おkせり");
    assert.equal(diff.replacement, "お<mark>かえ</mark>り");
});

test("long prefix typo focuses on the typo rather than dumping the prefix", () => {
    const source = "明日の午後の会議の資料をおkせりします";
    const replacement = "明日の午後の会議の資料をおかえりします";
    const diff = correctionDiff(source, replacement);

    assert.equal(diff.original, "…の資料をおkせりします");
    assert.equal(diff.replacement, "…の資料をお<mark>かえ</mark>りします");
    assert.ok(!diff.original.includes("明日の午後"));
});

test("earlier kanji difference does not expand highlight across the whole sentence", () => {
    const source = "昨日のうち合わせでお世話になてます";
    const replacement = "昨日の打合せでお世話になってます";
    const diff = correctionDiff(source, replacement);

    assert.equal(diff.original, "…お世話になてます");
    assert.equal(diff.replacement, "…お世話にな<mark>っ</mark>てます");
    assert.ok(!diff.original.includes("昨日"));
});

test("kana source fallback aligns particles and focuses on trailing typo", () => {
    const source = "きょうはお天気なのでさんぽにしよお";
    const replacement = "今日はお天気なので散歩にしよう";
    const diff = correctionDiff(source, replacement);

    assert.equal(diff.original, "…んぽにしよお");
    assert.equal(diff.replacement, "…散歩にしよ<mark>う</mark>");
});

test("typo at the start of a long sentence focuses on start and truncates tail", () => {
    const source = "おkせりなさい、明日の準備はもう終わりました";
    const replacement = "おかえりなさい、明日の準備はもう終わりました";
    const diff = correctionDiff(source, replacement);

    assert.equal(diff.original, "おkせりなさい、…");
    assert.equal(diff.replacement, "お<mark>かえ</mark>りなさい、…");
    assert.ok(!diff.original.includes("終わりました"));
});

test("typo in the middle of a long sentence truncates both ends symmetrically", () => {
    const source = "昨日の夜に友達と約束したばしょへ行く途中で迷子になった";
    const replacement = "昨日の夜に友達と約束した場所へ行く途中で迷子になった";
    const diff = correctionDiff(source, replacement);

    assert.equal(diff.original, "…と約束したばしょへ行く途中…");
    assert.equal(diff.replacement, "…と約束した<mark>場所</mark>へ行く途中…");
});

test("escapes HTML special characters inside diff and context", () => {
    const diff = correctionDiff("prefix<でおkせり&end", "prefix<でおかえり&end");
    assert.equal(diff.original, "…ix<でおkせり&end");
    assert.equal(diff.replacement, "…ix&lt;でお<mark>かえ</mark>り&amp;end");
});

test("oversized unmatched diff span trims head cleanly so end stays visible", () => {
    const source = "abcdefghijklmnopqrstuv";
    const replacement = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    const diff = correctionDiff(source, replacement);

    assert.equal(diff.original, "…ijklmnopqrstuv");
    assert.equal(diff.replacement, "…<mark>MNOPQRSTUVWXYZ</mark>");
});

test("extractFocusedHunk prefers ascii romaji typo hunk when earlier hunk differs", () => {
    const { hunk } = extractFocusedHunk(
        "会議の予定を変更してあしたおkせりします",
        "会議の予定を変えまして明日おかえりします",
    );
    assert.ok(hunk);
    assert.equal("会議の予定を変更してあしたおkせりします".slice(hunk.aStart, hunk.aEnd), "kせ");
});
