export type SettingsEntry = {
    url: string;
    page: string;
    section: string;
    label: string;
    keywords?: string;
};

/**
 * 設定検索（Ctrl+K）の索引。行を追加・改名したらここも更新する。
 * 索引と実画面のずれは settings-ui.browser.mjs の検索テストで検出する。
 */
export const settingsIndex: SettingsEntry[] = [
    { url: "/", page: "入力と文字", section: "文字の入力", label: "句読点", keywords: "、。 くとうてん" },
    { url: "/", page: "入力と文字", section: "文字の入力", label: "記号", keywords: "かっこ きごう" },
    { url: "/", page: "入力と文字", section: "文字の入力", label: "スペース", keywords: "空白 すぺーす" },
    { url: "/", page: "入力と文字", section: "文字の入力", label: "テンキー", keywords: "数字 てんきー" },
    { url: "/", page: "入力と文字", section: "半角・全角", label: "数字", keywords: "はんかく ぜんかく すうじ" },
    { url: "/", page: "入力と文字", section: "半角・全角", label: "英字", keywords: "はんかく ぜんかく えいじ alphabet" },
    { url: "/", page: "入力と文字", section: "半角・全角", label: "記号", keywords: "はんかく ぜんかく きごう" },
    { url: "/conversion", page: "変換と候補", section: "ZeroCon AI", label: "利用モデル", keywords: "モデル 選択 nano 先読みモデル タイポ補正モデル ZeroCon" },
    { url: "/conversion", page: "変換と候補", section: "ZeroCon AI", label: "バックエンド", keywords: "cpu vulkan gpu 推論" },
    { url: "/conversion", page: "変換と候補", section: "ZeroCon AI", label: "クイックベンチマーク", keywords: "速度 測定 ベンチ" },
    { url: "/conversion", page: "変換と候補", section: "ZeroCon AI", label: "GPUに載せる層数", keywords: "gpu layers vram" },
    { url: "/conversion", page: "変換と候補", section: "ZeroCon AI", label: "変換プロファイル", keywords: "ペルソナ 専門分野" },
    { url: "/conversion", page: "変換と候補", section: "確定", label: "長文の自動確定（ストリーミング確定）", keywords: "自動確定 ストリーミング" },
    { url: "/conversion", page: "変換と候補", section: "確定", label: "自動確定直後のBackspaceで未確定に戻す", keywords: "取り消し 巻き戻し" },
    { url: "/conversion", page: "変換と候補", section: "確定", label: "記号入力で変換を確定", keywords: "句読点 確定" },
    { url: "/conversion", page: "変換と候補", section: "候補と読みの表示", label: "Space / Tab まで候補を非表示", keywords: "候補 遅延 ひょうじ" },
    { url: "/conversion", page: "変換と候補", section: "候補と読みの表示", label: "予測変換（単語・フレーズ）", keywords: "よそく 予測変換 入力中 読み 単語 フレーズ" },
    { url: "/conversion", page: "変換と候補", section: "候補と読みの表示", label: "ライブ変換中の読みを表示", keywords: "読み ルビ ライブ" },
    { url: "/conversion", page: "変換と候補", section: "候補と読みの表示", label: "読み表示の高さ", keywords: "ルビ 高さ" },
    { url: "/conversion", page: "変換と候補", section: "タイポ補正と先読み", label: "タイポ補正", keywords: "ろーまじ ローマ字入力の辞書補正 ローマ字入力のタイポ補正 追加補正 タイポ 誤入力 促音 撥音 AI ZeroCon Model 文脈" },
    { url: "/conversion", page: "変換と候補", section: "タイポ補正と先読み", label: "文の先読み", keywords: "先読み さきよみ AIアシスト 追加補正と先読み 文 続き" },
    { url: "/conversion", page: "変換と候補", section: "タイポ補正と先読み", label: "タイポ補正の推論エンジン", keywords: "タイポ補正の実行方法 追加補正の推論エンジン lm studio 内蔵 モデル" },
    { url: "/conversion", page: "変換と候補", section: "変換学習", label: "学習と履歴の使用", keywords: "学習 履歴 削除" },
    { url: "/keys", page: "キーとローマ字", section: "クリップボード", label: "起動キー", keywords: "ショートカット クリップボード" },
    { url: "/keys", page: "キーとローマ字", section: "クリップボード", label: "履歴の保持時間", keywords: "クリップボード 保持" },
    { url: "/keys", page: "キーとローマ字", section: "英数・かな切り替え", label: "入力欄に合わせて自動切り替え", keywords: "英数 かな モード" },
    { url: "/keys", page: "キーとローマ字", section: "再変換", label: "再変換キー", keywords: "さいへんかん space" },
    { url: "/keys", page: "キーとローマ字", section: "ローマ字入力", label: "ローマ字テーブル", keywords: "ろーまじ へんかん" },
    { url: "/advanced", page: "詳細設定", section: "翻訳モード・選択文字翻訳（試験機能）", label: "翻訳機能を有効にする", keywords: "翻訳 translate" },
    { url: "/advanced", page: "詳細設定", section: "翻訳モード・選択文字翻訳（試験機能）", label: "コピー時に文字数・翻訳バーを表示", keywords: "コピー 文字数 翻訳 閉じる 消えない popup" },
    { url: "/advanced", page: "詳細設定", section: "翻訳モード・選択文字翻訳（試験機能）", label: "文字数・翻訳バーの自動消去", keywords: "自動 消去 秒 タイマー popup 翻訳" },
    { url: "/advanced", page: "詳細設定", section: "翻訳モード・選択文字翻訳（試験機能）", label: "翻訳サーバーを常時起動する", keywords: "翻訳 常時起動" },
    { url: "/advanced", page: "詳細設定", section: "翻訳モード・選択文字翻訳（試験機能）", label: "翻訳方向", keywords: "翻訳 英語 日本語" },
    { url: "/advanced", page: "詳細設定", section: "翻訳モード・選択文字翻訳（試験機能）", label: "翻訳モデル", keywords: "翻訳 0.8b 2b" },
    { url: "/about", page: "ZeroConについて", section: "アップデート", label: "バージョンと更新", keywords: "更新 アップデート version" },
];

function normalize(value: string): string {
    return value
        .normalize("NFKC")
        // カタカナをひらがなへ寄せ、読みの揺れで取りこぼさない。
        .replace(/[\u30a1-\u30f6]/g, (char) => String.fromCharCode(char.charCodeAt(0) - 0x60))
        .toLowerCase()
        .trim();
}

export function searchSettings(query: string): SettingsEntry[] {
    const normalized = normalize(query);
    if (!normalized) return [];
    return settingsIndex
        .map((entry) => {
            const label = normalize(entry.label);
            const target = normalize(`${entry.label} ${entry.section} ${entry.page} ${entry.keywords ?? ""}`);
            // 名称一致を先に、説明・別名一致を後に並べる。
            const rank = label.startsWith(normalized) ? 0 : label.includes(normalized) ? 1 : target.includes(normalized) ? 2 : -1;
            return { entry, rank };
        })
        .filter((result) => result.rank >= 0)
        .sort((a, b) => a.rank - b.rank)
        .slice(0, 12)
        .map((result) => result.entry);
}
