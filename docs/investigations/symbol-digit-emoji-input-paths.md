# 数字・記号・絵文字の入力経路比較（Windows / Android）

作成日: 2026-11-08

状態: **調査レポート**。2026-11-08 に改善提案の一部を実装（下記「実装状況」参照）。実IME・実端末での確認は未実施。

## 実装状況（2026-11-08 追記）

本レポートの改善提案のうち、以下3件を実装した。実IME・実端末での確認は未実施。

| 項目 | 状態 | 主な変更 |
|---|---|---|
| Android: SYMBOLS パネルで composition を破棄しない | 実装済み | 新規 `android/app/src/main/java/com/zeroconvert/ime/PanelTextCommit.kt`、`ZeroConvertInputMethodService.kt` の `commitPanelText` / 配線、`PanelTextCommitTest.kt` |
| Android: 動的マクロの実用化 | 実装済み | 下記のとおり「接続」の実態を訂正。新規 `engine/MacroCandidateMerge.kt` とテスト。デッドコード `assist/DynamicMacro.kt` を削除 |
| Windows: 先読みの数字・記号末尾ガード | 実装済み | `windows/crates/server/src/ai_assist/prediction.rs` の `is_continuation_tail` とテスト、`docs/predictive-assist.md` 更新 |

### Android: 記号パネル

- `PanelTextCommit.decide` が「翻訳モード / composition 空・英語バッファ / 候補一致 / それ以外」を判定し、候補に一致しない記号・数字は `confirmAsync(trailingText = text)` で**読みを変換確定してから記号を追記**する。`commitText` の `surfaceAfter` 空→`clearInput()` により読みが破棄される経路を通らない。
- 記号は `trailingText` として渡るため、`ConversionCommitLearning.capture` の `committedText == candidate.text + trailingText` 条件により学習表面には含まれない。
- SYMBOLS パネルの半角数字 `1..0`・全角数字 `１..０` も同じ判定（composition があれば変換結果の後に数字を追記、なければ直接確定）。
- 絵文字（`commitEmoji`）は今回変更していない。`confirmCurrent()` 後に即 `commitText(emoji)` する非同期確定の既知課題が残る。

### Android: 動的マクロ（レポート第3節の記述を訂正）

- レポート初版の「`DynamicMacro.kt` は pipeline 未接続のデッドコード」は Kotlin 側としては正しいが、**動的マクロ自体は共有Rustエンジン経由で既に動作していた**。`engine/WindowsEngine.kt` が `op=macros` を要求し、`engine/rust/src/bridge.rs:148` が `ai_assist::current_expansions` を返す。`KanaKanjiConverter` の Kotlin 候補生成は `BuildConfig.WINDOWS_ENGINE=true` で早期 return するため製品経路ではない。
- 問題は Android がマクロを**全候補の末尾**に追加していたこと。`MacroCandidateMerge.merge` でデスクトップ Windows と同じ「先頭候補の直後（index 1）」に挿入し、重複を除外、`learningAllowed=false` を維持するよう変更した。
- Kotlin の `assist/DynamicMacro.kt` は削除した。`KanaKanjiConverter` に第二のマクロ実装は追加しない（`android/rebuilt/AGENTS.md` の第二パイプライン禁止規約）。`docs/android-conversion-logic.md` の 8.5 節も共有Rustエンジン供給に更新済み。

### Windows: 先読みガード

- `prediction::suggest` に `is_continuation_tail` を追加。読みの末尾が「ひらがな `ぁ..ゖ` / カタカナ `ァ..ヶ` / `ー` / 日本語句読点（`、。，．！？!?`）」の場合のみ推論を許可し、ASCII 数字・全角数字・その他の記号・英字・漢字は `None`。キャッシュ参照・`IN_FLIGHT` 取得・HTTP 呼び出しより前に判定する。
- `だい1` `ねん3` `だい#` では先読みが出ず、`だい1の` のように末尾がかななら従来どおり発火する。

### 検証結果

- Android: `:rebuilt:testDebugUnitTest`（JVM 256件成功、新規 `PanelTextCommitTest` 6件・`MacroCandidateMergeTest` 9件）、`:rebuilt:lintDebug`、`:rebuilt:assembleDebug` が成功。ただし Windows 側の共有ソース変更で `verifySharedEngine` が stale になるため `-x :rebuilt:verifySharedEngine` を付けて実行した。
- Windows: `cargo test -p azookey-server --lib --offline`（71 passed）、`--bin azookey-server --offline`（86 passed）。
- 未実施: 実IME・実端末での確認、Android 共有エンジン native の再生成（`scripts/build-android-shared-engine.ps1`）、Windows の `Build-Trial.bat` 適用。Android の端末確認前に共有エンジン再生成が必要。

目的: かな入力・かな漢字変換のパイプラインに、数字・記号・絵文字がどう流入するかを Windows と Android の両方で追い、違いと改善案を整理する。パスはリポジトリルートからの相対パス。`W/` = `windows/`、`A/` = `android/app/src/main/java/com/zeroconvert/ime/` を示す。

関連: 翻訳機能側の「数字のみ・記号のみcomposition」問題は [translation-improvements-plan.md](translation-improvements-plan.md) 項目2・4 を参照。

---

## 1. Windows の経路

### 1-1. キー入力の受け口（TSF）

- `windows/crates/client/src/engine/user_action.rs:71-224` `TryFrom<usize> for UserAction`:
  - 数字上段 `0x30..=0x39` とテンキー `0x60..=0x69` は Shift 非押下時のみ `UserAction::Number { value, is_numpad }`（行93-177）。Shift+数字（`!` `#` 等）と一般記号は ToUnicode フォールバックで `UserAction::Input(char)`（行196-218）。
  - テンキー記号 `0x6A..=0x6F` は `UserAction::NumpadSymbol('*' '+' ',' '-' '.' '/')`（行184-189）。
  - 半角/全角キー（0xF3/0xF4）は `ToggleInputMode`（行194）。
- TSF入口は `windows/crates/client/src/tsf/key_event_sink.rs:39-218`。数字・記号・絵文字パネル専用の PreservedKey 登録はない。
- **絵文字パネル（Win+.）は windows/ のコードに処理が存在しない**。OS が IME の KeyEventSink を経由せずアプリへ直接挿入するため、組成バッファ・変換・タイポ補正・先読みは一切発火しない（組成アクティブ時の OS 挙動は実IME要確認）。

### 1-2. かなモードでの振る舞い（`windows/crates/client/src/engine/composition.rs`）

`plan_actions_for_user_action_with_lookup`（行5210-5706）より:

- CompositionState::None + Kana モード:
  - `Input(char)`（記号含む）→ `StartComposition + AppendText` で**未確定バッファに入る**（行5319-5325）。
  - `Number`（上段）→ `AppendText("0".."9")` で**数字もバッファに入る**（行5342-5351）。
  - `Number`（テンキー）→ `numpad_text_for_mode`（行870-882）で幅を決め `AppendTextRaw`。`NumpadInputMode::DirectInput` は素通し（未処理=アプリへ直接）。
  - `NumpadSymbol` → `AppendTextRaw`（行5308-5318）。
  - `Space` → `space_input` 設定+Shift で半角/全角を決めて**即確定**（行5352-5366）。
- Composing 中:
  - 先頭分岐が**句読点自動確定**。`punctuation_commit_action_target_enabled`（行5051-5084、対象は `,` `.` `!` `?` 全半角・行4971-4983、設定 `punctuation_commit*` 既定OFF）が真なら `EndComposition + CommitTextDirect` で直接確定（行5394-5412、行5087-5095）。それ以外の記号・数字はバッファに追記（行5429-5460）。
- temporary_latin / Latin モード: `direct_text_for_action`（行779-792）が Some なら `AppendTextDirect`（ローマ字変換を通さない）。`normalize_direct_symbol_char`（行810-826）が全角記号を ASCII へ正規化。
- 英数モードの `plan_english_action`（`composition/english.rs:46-137`）: 英字のみ組成に入り、**数字・記号は `EndComposition + CommitTextDirect` で直接確定**（行119-137）。
- InputScope が `IS_DIGITS` `IS_NUMBER` `IS_TELEPHONE_*` `IS_CURRENCY_*` 等なら `InputMode::Latin` に強制（`engine/input_scope_mode.rs:37-105`、設定 `follow_input_scope_mode_enabled`）。

### 1-3. 記号・数字のクライアント側変換

- `resolve_symbol_input_text_with_lookup`（composition.rs:1035-1078）: かなモードで記号1文字入力時、
  1. 複数文字ローマ字の継続になり得る記号はローマ字として扱う（Zenzai 有効時）。
  2. **カスタムローマ字テーブルの単一記号ルール**（`engine/romaji_lookup.rs:29-43,114-124` `single_symbol_outputs`）を優先適用。
  3. なければ `convert_kana_symbol`（`engine/full_width.rs:243-306`）で句読点スタイル・記号スタイル・幅を適用。数字は幅グループ `number`（既定 half）で全角化され得る（行111-127, 361-449）。
- **「いち→1」「えん→¥」のような読み→数字・記号変換はビルトイン辞書に存在しない**。カスタムローマ字テーブルの単一記号行か、下記ダイナミックマクロの計算候補のみ。

### 1-4. サーバ側（ローマ字変換・候補生成）

- `AppendText` / `AppendTextRaw` は IPC 経由で `INPUT_STYLE_ROMAN2KANA=0` として Swift へ渡る（`crates/server/src/main.rs:2098-2187` `append_text`）。`AppendTextDirect` のみ `INPUT_STYLE_DIRECT=1`。
- Swift 側 `server-swift/Sources/azookey-server/azookey_server.swift:2998-3013` `append_text`: `composingText.insertAtCursorPosition(inputString, inputStyle:)`。ローマ字→かな変換は upstream azooKey の ComposingTable が行い、**数字・記号はテーブル不一致のため convertTarget（読み）にそのまま残る**。
- 候補生成:
  - 通常辞書 + ユーザー辞書 + 学習。
  - **絵文字候補**: `EmojiTextReplacerCache`（azookey_server.swift:1336-1356）+ `emoji_all_E15.1.txt`（サブモジュール azooKey_emoji_dictionary_storage、ルート `.gitmodules:4-6`）を `ConvertRequestOptions.textReplacer` に渡す（行1378-1380）。**絵文字が変換候補に入る唯一の経路**。絵文字固有のユーザー設定はない。
  - Zenzai（zenz v3）: `makeConvertRequestOptions`（同ファイル行1358-1397）。`leftSideContext`（確定済み前文、半角空白→U+3000）+ convertTarget。**数字・記号は読みにそのまま包含されフィルタなし**。1文字記号入力は `minInputCountForZenzaiCandidates`（行1457-1465）で Zenzai 候補が出ない。
  - ダイナミックマクロ（Rust server）: `crates/server/src/main.rs:101-134` → `ai_assist/dynamic_macro.rs:451-513` `tokenize_arithmetic`。全角/半角数字と `+−×÷x()` を受理する**四則計算候補**と日時展開。
- F6-F10（`set_text_type_for_function`, composition.rs:768-776）: ひらがな/カタカナ/半角ｶﾅ/全角英数/半角英数。非かな文字（数字・記号）は `to_katakana` 系を素通し。

### 1-5. タイポ補正・先読み（ai_assist）

入口は `crates/server/src/main.rs:136-237` `append_ai_assist_candidates`。読みに数字・記号が混ざっても即除外はされない。各生成器の個別条件:

- **keyboard_hybrid**（`ai_assist/keyboard_hybrid.rs:296-300,366`）: 元打鍵列が ASCII 小文字のみ・4〜64文字。**数字・記号混入で不発**。
- **モデル補正 corrector**（`ai_assist/corrector.rs:197-306`）: 数字・記号を含む読みでも推論する。ただし証拠判定 `supported_reading_change`（行39-46）がかな/漢字を要求し、`reading_change_distance`（行18-37）の表面検証は「かな+句読点のみ」に制限。
- **ローマ字混入デコーダ**（`ai_assist/romaji_decoder.rs:62-119`）: 復元結果が `[a-z-]` 以外（記号・絵文字入り）なら None。
- **先読み prediction**（`ai_assist/prediction.rs:81-110`）: **読みの末尾が ASCII 英字のみ不発**（未確定ローマ字ガード）。**末尾が数字・記号なら発火する**。応答フィルタ `useful`（行31-38）は記号のみの継続を拒否。
- プロンプトは読みをカタカナ化して PUA マーカ `\u{EE00}`（読み開始）/`\u{EE01}`（読み終了）/`\u{EE02}`（文脈開始）でラップ（corrector.rs:114-145, prediction.rs）。`to_katakana` は対応外の数字・記号・絵文字を素通し。

### 1-6. Windows の設定項目

Rust 定義 `windows/crates/shared/src/lib.rs`、UI `windows/frontend/src/pages/general.tsx`:

| 設定 | 定義 | 既定・備考 |
|---|---|---|
| `numpad_input` | lib.rs:552-557 | direct_input / always_half / follow_input_mode |
| `space_input` | lib.rs:543-548 | スペースの幅 |
| `punctuation_style` / `symbol_style` | lib.rs:523-539 | 句読点・括類・スラッシュの全半角 |
| `character_width.groups`（`number` 等11種） | lib.rs:569-601 | `number` 既定 half |
| `symbol_fullwidth` | lib.rs:462-512 | 42種の記号別全角マップ。UI 個別編集は未確認 |
| `punctuation_commit*` | lib.rs:688-694 | 既定OFF |
| 絵文字関連 | — | **設定項目なし**（辞書はバンドル固定） |

幅・記号・テンキー系設定はすべて Rust クライアント側で適用され、Swift へは渡らない（Swift 受け渡しは `jev_conversion` / `experimental_typo_correction` / `predictive_conversion` / `romaji_table` 等、azookey_server.swift:1399-1436）。

---

## 2. Android の経路

### 2-1. キーボード UI の受け口

- `A/ComposeKeyboardView.kt:56-67`: `KeyboardMode { JAPANESE, ENGLISH, NUMERIC }` と `KeyboardTab { MAIN, CLIPBOARD, EMOJI, SYMBOLS }`。**数字はモード、記号・絵文字はタブ（パネル）**。
- `onCycleMode`（同ファイル行415-427）: 「あa1」キーでモード循環。切替前に未確定 composition を確定（`ZeroConvertInputMethodService.kt:387-392`）。
- EMOJI/SYMBOLS/CLIPBOARD タブへは composition の確定もキャンセルもしない（同ファイル行132-136, 442-451）。
- `follow_input_type_mode`（既定 false、`InputTypeMode.kt:18-45`）で `TYPE_CLASS_NUMBER/PHONE/DATETIME` → `NUMERIC`、URI/email 等 → `ENGLISH` へ自動切替。

### 2-2. 種別ごとの振る舞い

- **数字（QWERTY、かなモード）**: `qwerty_number_row` 設定（既定 false）で最上段 `1..0`、Shift で `!@#$%^&*()`。無効でも q..p の上フリックで到達（`QwertyNumberRow.kt:8-18`）。すべて `handleChar()`（`ZeroConvertInputMethodService.kt:1736-1811`）→ `composingText.insertAtCursorPosition(ch, InputStyle.ROMAN2KANA)`（行1778）で**変換バッファに入る**。コメント（行146-151）に「Digits are excluded: Windows の `UserAction::Number → AppendText` と同様」と明記。
- **数字（NUMERIC テンキー）**: フリックで `☆ ¥ % ○ + < 「 〒 ^ (` 等も（`FlickKeyboardView.kt:191-222`）。出力は常に `onCommitText` → `commitDirect`（`ZeroConvertInputMethodService.kt:2552-2560`）で**直接確定・変換なし**。
- **記号（即確定文字）**: `isImmediateCommitChar`（`ZeroConvertInputMethodService.kt:152-155`）= `、 。 . , ? ! ？ ！ ． ，` と `!@#$%^&*()`。composition あれば `confirmAsync(trailingText=punc)`（読みを確定してから記号追加）、なければ `commitDirect`。**変換対象外**。
- **記号（SYMBOLS パネル「!?#」キー）**: 8カテゴリのハードコード辞書（半角数字・全角記号・括類・矢印・数学記号・囲み/ローマ数字・assorted・[全] 全角英数・[￥] 通貨。`ComposeKeyboardView.kt:2355-2431`）。選択で `commitText(sym)`（行658-682 → `ZeroConvertInputMethodService.kt:2708-2783`）で**即確定**。composition が非空でもマッチ候補がなければ学習不成立で記号のみコミットし composition を破棄（`clearInput()`）。**記号は変換バッファに入らず composition と混ざらない設計**。
- **絵文字（EMOJI パネル「😀」キー）**: `assets/emoji_catalog.tsv`（約1,900行、`EmojiCatalog.kt:24-27`。日本語キーワード検索・肌色ロングプレス・最近使用32件対応）。選択で `commitEmoji(emoji)`（`ZeroConvertInputMethodService.kt:2562-2578`）: **「絵文字は literal text として確定し、読みベースの変換・学習経路に入れてはならない」と明記**。composition あれば先に `confirmCurrent()` してからコミット。
- **英数モード**: 英字は英単語補完バッファへ。数字・記号は `handleEnglishChar`（`ZeroConvertInputMethodService.kt:2580-2607`）で `!ch.isLetter()` → `commitDirect` 即確定。
- **テンキーの Space**（`FlickKeyboardView.kt:1466`）は `onCommitText(" ")` で composition を先に確定しない経路（かなモードの `handleSpace()` と不整合）。

### 2-3. 変換パイプライン内での数字・記号

- ローマ字→かな変換 `azookey/input/DefaultRomanToKana.kt:13-315` に**数字・記号の規則は存在しない**。未一致文字は `azookey/input/InputTable.kt:182-189` のフォールバックで**リテラルとして surface に追加**=読みに残り変換対象になる。
- **Kotlin 側に「いち→1」「えん→¥」の専用辞書はない**。¥ はテンキー2のフリック・記号パネル[￥]・絵文字カタログのキーワード（💱「えん」等）のみ。
- `assist/DynamicMacro.kt`（Kotlin版、旧デッドコード）は削除した。日時・計算などの決定的展開は共有Rustエンジン（`engine/rust/src/bridge.rs` の `op=macros` → `ai_assist::current_expansions`）が供給し、Android の `WindowsEngine` が `MacroCandidateMerge` で先頭候補の直後（index 1）に挿入する。
- zenz へのプロンプト: `zenz_jni.cpp:271-286`。`\uEE03{profile}` + `\uEE02{leftContext}\uEE00{読み}\uEE01`。**数字・記号はカタカナ化されずそのまま埋め込まれ** llama_tokenize に渡る。出力側 `safe_new_string`（同ファイル行179-251）は PUA 制御トークン除去と**4バイトUTF-8（絵文字等）のサロゲートペア変換**を実装済み。

### 2-4. 候補生成（`KanaKanjiConverter.kt:528-714` `requestCandidates`）

優先順: 学習メモリ完全一致 → 学習プレフィックス予測 → 学習セグメント連鎖 → ユーザー辞書 → **ZeroCon AI 変換（zenz）** → **LOUDS 辞書** → **絵文字辞書候補**（`emoji_all_E15.1.txt` を読み(ひらがな)→絵文字で最大12個/読み、cost 500 で AI・辞書上位の直後に挿入、行87-117, 690-696, 507-509）→ 前方一致辞書 → ひらがな/カタカナフォールバック。

- **絵文字候補は Windows（upstream TextReplacer）と異なり Kotlin 側で独立実装**。
- 顔文字専用辞書・記号候補生成はなし。半角/全角の自動変換機能もなし（記号パネル[全]カテゴリの手入力のみ）。

### 2-5. タイポ補正・先読み

- 発火条件（`ZeroConvertInputMethodService.kt:2067`）: composition あり・非 sensitive・AI assist 有効・**末尾未確定ローマ字なし**（`AssistInference.kt:20` `hasPendingRomaji`）。**読みに数字・記号が含まれても発火し得る**（Windows の prediction と同様）。
- タイポ補正 `ModelCorrector`（`assist/ModelCorrector.kt:10-59`）: 候補生成は字母・かなの隣接/欠母音のみで数字・記号の隣接ミスは生成しない。辞書照合は**かなのみの候補に限定**（`TypoCorrectionEngine.kt:306-314, 339-348` `isKanaOnly`）。
- 先読み `ModelPredictor`（`assist/PredictiveAssistEngine.kt:6-33`）: `useful()` は生成継続に `isLetterOrDigit()` を要求。数字入り読みでもモデルには渡る。
- 自動適用は `AutoCorrectionEvidence.permits` + `ReadingCheck.hasSameReading`（`ReadingCheck.kt:63-80`。数字・記号単独は `literal()` で ASCII 32..126 を文字通り読み扱い）で抑止。
- 絵文字・記号パネル・テンキーの直接確定は変換・学習・補正の対象外。

### 2-6. Android の設定項目

- `qwerty_number_row`（`ZeroConvertInputMethodService.kt:100`、既定 false）— QWERTY 数字行表示。
- `emoji_recent`（行117）— 絵文字の最近使用（履歴のみ、ON/OFF設定なし）。
- `follow_input_type_mode`（行101、既定 false）— 数値欄での自動 NUMERIC 切替。
- 絵文字候補（emoji_all_E15.1）の ON/OFF 等の設定は存在しない。

---

## 3. 両OSの比較

| 観点 | Windows | Android |
|---|---|---|
| 数字（かなモード QWERTY/上段） | composition に入り変換対象（`UserAction::Number → AppendText`） | composition に入り変換対象（`handleChar → ROMAN2KANA`）。**同一方針** |
| 数字（専用キー/テンキー） | `numpad_input` 設定で direct/半角/モード追従（既定 direct=素通し） | NUMERIC モード・フリック記号含め `commitDirect` 即確定 |
| 記号（かなモード直接入力） | composition に**追加される**（句読点自動確定設定ONなら `,` `.` `!` `?` は先に確定してから追加扱い） | `、 。 . , ? ! ？ ！` と `!@#$%^&*()` は**即確定**（読みを確定してから記号追加） |
| 記号パネル | なし（キーボード直打のみ。IME側に記号パネル実装なし） | SYMBOLS パネル8カテゴリ、即確定・composition 破棄 |
| 記号の幅・種類変換 | `punctuation_style` / `symbol_style` / `character_width.groups` / `symbol_fullwidth`（42種）で高細度に制御 | **自動変換なし**。パネルから手入力で選ぶ |
| 絵文字候補（変換内） | upstream azooKey TextReplacer（emoji_all_E15.1）経由 | Kotlin 独立実装。読み完全一致・cost 500・最大12個 |
| 絵文字パネル | **なし**（OS の Win+. に依存。IME パイプライン外） | 自前 EmojiPanel（検索・肌色・最近使用）。`commitEmoji` で変換・学習対象外として確定 |
| 「いち→1」「えん→¥」系 | ビルトイン辞書なし。カスタムローマ字テーブル単一記号ルール + dynamic_macro 計算候補 | ビルトイン辞書なし。マクロは共有Rustエンジン供給（`MacroCandidateMerge` で index 1 に挿入）。Kotlin版 `DynamicMacro` は削除済み |
| 半角/全角切替 | 半角/全角キー + 幅グループ設定で組成中文字に適用 | 自動変換なし（F6-F10 相当もなし） |
| InputScope/InputType 追従 | `follow_input_scope_mode_enabled` | `follow_input_type_mode`（既定ともに無効） |
| zenz プロンプト | PUA マーカ + 読み素通し（数字・記号含む） | 同構造。出力側は絵文字（SMP）サロゲート変換実装済み |
| タイポ補正 | 数字・記号入り読みで発火し得る（keyboard_hybrid は ASCII 小文字限定で不発） | 発火し得る。ただし辞書照合は `isKanaOnly` で数字・記号入り候補を除外 |
| 先読み | 末尾が ASCII 英字のみ不発。**末尾が数字・記号なら発火** | 末尾未確定ローマ字がなければ発火。継続候補は `isLetterOrDigit` 制限 |

### 本質的な違い（要点）

1. **記号のバッファ扱いが逆**: Windows はかなモードの記号を composition に追加して変換対象にする（幅・スタイル設定で変換）。Android は記号を即確定し、composition と混ざらないことを仕様とする。翻訳機能の項目2（数字のみ・記号のみcomposition）と同根の設計差。
2. **絵文字の入口が候補かパネルかで分担が違う**: Windows は変換候補への絵文字混入（TextReplacer）のみでパネルは OS 依存。Android は自前パネル（検索・履歴付き）+ 読み完全一致の絵文字辞書候補。Android の候補は読み完全一致のみで部分一致・融通が利かない。
3. **記号・幅の設定機能が Windows に偏る**: Android には punctuation/symbol style・幅グループ相当がなく、全角記号はパネル手入力。
4. **数字展開マクロの有無**: Windows は四則計算・日時マクロが動作中。Android も共有Rustエンジン経由（`op=macros`）で同じマクロが動作しており、Kotlin 側の `DynamicMacro` は削除済み。
5. **記号パネル選択時の composition 破棄**（Android 固有）: composition 中に記号パネルを使うと読みが捨てられる（学習も不成立）。Windows は同場面で composition 追加が可能。

---

## 4. 改善提案

優先度は「タイポ補正の安定動作と先読み精度の改善」「変換操作を減らす快適さ」への寄与で評価。

### 高

1. **Android: SYMBOLS パネル選択時の composition 破棄をやめ、Windows と同様の「読みを確定してから記号を追加」に統一（実装済み: 上記「実装状況」参照）**（`ZeroConvertInputMethodService.kt:2708-2783` `commitText` 内の不一致時 `clearInput()` 経路）。`isImmediateCommitChar` と同じ `confirmAsync(trailingText=)` 経路に寄せる。破棄される現在の読みが失われ、打ち直しが発生するため変換操作削減に直結。
2. **Windows: 記号入力時の先読み（prediction）の誤発火ガードを確認・調整（実装済み: 上記「実装状況」参照）**。`ai_assist/prediction.rs:81-110` は末尾が数字・記号でも発火する。記号単独入力（例: `、` 直後）や全角記号の直後に不適切な継続候補が出ないか実IMEで確認し、必要なら「読みがかなを含まない場合は先読みしない」等のガードを追加。タイポ補正側の `supported_reading_change` と同じ水準に揃える。
3. **「いち→1」「えん→¥」「にじゅうえん→¥200」の読み→数字・記号変換の提供（実装済み: 上記「実装状況」参照）**。両OSともビルトイン辞書にない。
   - Windows: 既存 dynamic_macro の仕組に単位・数量展開（`えん`→`¥`、`ぜん`→`￥` 等）を追加するか、カスタムローマ字テーブルの単一記号ルールを利用する手順を設定UI・ドキュメントに明示。
   - Android: 共有Rustエンジンのマクロ候補を先頭候補直後に配置するよう `MacroCandidateMerge` を追加し、Kotlin版 `DynamicMacro.kt` は削除した（実装済み）。

### 中

4. **Android: 絵文字候補の検索改善**。現状は読み完全一致（`lookupEmojis(hiragana)`）のみ。前方一致・キーワード検索（emoji_catalog の keywords）との連携で「ありがとう→🙏」等の到達率が上がる。パネル検索とのロジック共通化が望ましい。
5. **Android: 記号・幅の設定の最小セット導入**。`punctuation_style`（、，／。．）と数字の全半角だけでも Windows と揃えると、両OSで同じ打鍵が同じ結果になる。既存の Windows 幅グループ実装（`full_width.rs`）のサブセットで実装可能。
6. **Android: テンキー Space の composition 不整合修正**（`FlickKeyboardView.kt:1466`）。composition ありの場合に先確定してから空白を入れる（かなモード `handleSpace()` と同一挙動に）。
7. **Windows: Win+. 絵文字パネルと組成の干渉確認**。組成アクティブ時に OS 絵文字パネルから挿入した場合の動作（組成の破棄・残存、未確定文字列の化け）を実IMEで確認し、問題あれば TSF 側で組成確定後に挿入を許す処理を検討。
8. **絵文字候補・パネルの ON/OFF 設定**（両OS）。絵文字辞書候補は cost 500 で常に挿入され、業務入力ではノイズになり得る。設定項目の追加を検討（現状、両OSとも設定不可）。

### 低

9. **Windows: `symbol_fullwidth` の個別編集 UI**。設定ファイル項目としてのみ存在する（`shared/src/lib.rs:462-512`）ため、設定画面から編集可能にする。
10. **zenz モデルの数字・記号・絵文字トークン対応の確認**。トークナイザは GGUF 依存でリポジトリ外。`benchmarks/` で数字・記号入り読み（「だい1」「3ねん」等）と絵文字を含む文脈の変換精度を評価し、語彙不足なら学習側（`training/`）へ課題を上げる。実IMEの確認は別途必要。

### 実施上の注意

- いずれも「入力順序・読み・未確定ローマ字・文字数・候補ID・文脈を別々に扱う」既存原則を維持すること。特に記号の即確定/バッファ追加の統一は、タイポ補正・先読み・翻訳（項目2・4）の発火条件に波及するため、回帰テスト（Android: `QwertyNumberRowTest.kt` 等、Windows: composition の該当テスト）を先に確認する。
- 両OSで方針を統一する項目（記号のバッファ扱い、数字展開）は、既存ユーザーの挙動を変えないよう設定化・既定維持を前提にする。

---

## 5. 不明点

| 項目 | 内容 |
|---|---|
| Windows | 組成アクティブ時の Win+. 絵文字挿入の OS 挙動（コード不存在のため実IME確認が必要） |
| 両OS | zenz/ZeroCon モデルの GGUF トークナイザが数字・記号・絵文字をどの程度語彙化しているか（モデル評価で確認） |
| Android | LOUDS 辞書の文字表（`charID.chid`）に数字・記号が含まれるか（バイナリ依存。含まれれば「いち→1」系の辞書語が出る可能性） |
| Windows | `symbol_fullwidth` の設定 UI からの個別編集画面の有無 |
| Android | `DynamicMacro`（Kotlin版）は共有Rustエンジン供給への一本化に伴い削除済み。マクロ自体は以前から共有Rustエンジン経由で動作していた |
