# 詳細調査・個別実装メモ (`docs/investigations/`)

個別機能の仕様・設計メモ、実装計画（実施状況つき）、特定バージョンでの不具合解析・回帰調査の記録を格納するディレクトリです。記載・整理ルールは [../documentation-guide.md](../documentation-guide.md) を参照してください。

## 現行機能・仕様・比較ドキュメント

- [windows-translation-input.md](windows-translation-input.md): Windows 版の翻訳入力（モード・選択翻訳・PLaMo 3 固定リビジョン）
- [android-translation-input.md](android-translation-input.md): Android 版の翻訳入力（オンデマンドDL・JNI Runtime・入力経路）
- [windows-inference-memory.md](windows-inference-memory.md): Windows 推論時のメモリ・GPU 層数・バックエンド切替仕様
- [boundary-context-reconversion.md](boundary-context-reconversion.md): 自動確定・文途中入力における境界文脈を保持する継続変換の仕様と実装
- [browser-ime.md](browser-ime.md): ブラウザ体験版（Swift/Rust/llama.cpp Wasm・GitHub Pages）の構成
- [symbol-digit-emoji-input-paths.md](symbol-digit-emoji-input-paths.md): 数字・記号・絵文字の入力経路の両 OS 比較調査（一部改善実装済み）

## 実装済み・進行中の計画書（設計・検証記録）

- [android-input-type-mode.md](android-input-type-mode.md): **実装済み** — 入力先の `inputType` に合わせたキーボードモード自動切替（現行: [android-conversion-logic.md](../android-conversion-logic.md) / `InputTypeMode.kt`）
- [windows-input-scope-mode.md](windows-input-scope-mode.md): **実装済み** — 入力先の `InputScope` に合わせた入力モード自動切替（現行: [windows-conversion-logic.md](../windows-conversion-logic.md) / `input_scope_mode.rs`）
- [windows-copy-translation-plan.md](windows-copy-translation-plan.md): **実装済み** — 入力欄外コピーからの文字数・翻訳ポップアップ（`selection-ui` / `copy_translation.rs`）
- [translation-improvements-plan.md](translation-improvements-plan.md): **実装済み** — 翻訳入力のコールドスタート待ち・数字のみ除外・エラー細分化・プロンプト同期
- [input-policy-sharing-plan.md](input-policy-sharing-plan.md): **実装済み** — 入力ポリシー・自動確定判定の両 OS 共有（`conversion_policy.rs` + `tests/input-policy/`）
- [learning-deletion-plan.md](learning-deletion-plan.md): **実装済み** — 学習削除の共通化（スコープ別リセット・候補単位削除）
- [browser-ime-parity-plan.md](browser-ime-parity-plan.md): **段階実装中** — Web 版での Windows パリティ再現計画（検証記録: [browser-ime-parity-2026-11-17.md](../../reports/web/browser-ime-parity-2026-11-17.md)）
- [dictionary-learning-architecture-plan.md](dictionary-learning-architecture-plan.md): **実装進行中（Step 1 完了・Step 2〜5 一気通貫実装仕様）** — モデル重視変換（純粋プロンプト＋評価時ユーザー辞書保護）・文節連鎖による文再現・10,000件ユーザー辞書刷新・永続化統合の設計・検証仕様（共有エンジン軸・Windows 優先実装）
- [additional-dictionary-plan.md](additional-dictionary-plan.md) — SudachiDict FullとWikidataを使うWindows試験辞書の生成・切り替えと採用判定

## 過去の調査・バージョン別修正ログ

> [!NOTE]
> 以下の `android-*` 記録は主に 2026-10〜11 の特定 dev 版（2.0.1-dev 〜 2.0.11-dev）における不具合解析・修正ログです。なお `:app` モジュールは 2026-11-08 に廃止され `:rebuilt` へ一本化されています。

- [android-arrow-commit-long-input-crash.md](android-arrow-commit-long-input-crash.md): 矢印キーによる生かな確定と長文 `n_batch` クラッシュの原因調査（後続で `n_batch=1024` / 境界ガード・カーソル修正を実施）
- [android-cursor-arrow-handling.md](android-cursor-arrow-handling.md): 矢印キーによるカーソル移動時のひらがな化・意図しない確定の修正（2.0.11-dev）
- [android-cursor-ownership.md](android-cursor-ownership.md): カーソル移動時の所有権判定修正（2.0.10-dev）
- [android-mode-switch.md](android-mode-switch.md): 入力モード切替時の英字保持仕様（2.0.9-dev）
- [android-pending-romaji.md](android-pending-romaji.md): 入力途中の未完ローマ字保護仕様（2.0.8-dev）
- [android-clipboard-suggestion.md](android-clipboard-suggestion.md): クリップボード候補表示の期限・設定（2.0.7-dev）
- [android-cursor-prefix-regression.md](android-cursor-prefix-regression.md): カーソル前変換の復旧と検証（2.0.6-dev）
- [android-selection-count.md](android-selection-count.md): 選択範囲の文字数カウント（2.0.4-dev）
- [android-prefix-cursor.md](android-prefix-cursor.md): カーソル左側の変換制御（2.0.3-dev）
- [android-shift.md](android-shift.md): QWERTY Shift キー制御（2.0.2-dev）
- [android-candidate-scroll.md](android-candidate-scroll.md): 候補バーの先頭表示・スクロール挙動（2.0.1-dev）
- [android-english-enter.md](android-english-enter.md): 英字モード Enter 確定の修正
- [android-keyboard-ui-stability.md](android-keyboard-ui-stability.md): キーボード UI 高さの安定化
