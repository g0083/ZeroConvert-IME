# 詳細調査・個別実装メモ (`docs/investigations/`)

特定バージョンの不具合解析、個別機能の実装メモ、回帰調査などの記録を格納するディレクトリです。

## 主なドキュメント

- `android-clipboard-suggestion.md`: クリップボード候補表示の仕様・検証
- `android-cursor-ownership.md`: カーソル移動時の所有権判定
- `android-cursor-prefix-regression.md`: カーソル前変換の復旧と検証
- `android-mode-switch.md`: 入力モード切替時の英字保持仕様
- `android-input-type-mode.md`: 入力先の inputType に合わせたキーボードモード自動切替（実装計画）
- `windows-input-scope-mode.md`: 入力先の InputScope に合わせた入力モード自動切替（実装計画）
- `translation-improvements-plan.md`: 翻訳入力の残り改善計画（コールドスタート待ち・数字のみcomposition・エラー細分化・12キー数字注入・プロンプト同期）（実装計画）
- `symbol-digit-emoji-input-paths.md`: 数字・記号・絵文字の入力経路の両OS比較（調査レポート・改善提案）
- `android-pending-romaji.md`: 入力途中のローマ字保護仕様
- `android-prefix-cursor.md`: カーソル前入力の制御
- `android-selection-count.md`: 選択範囲の文字数カウント
- `android-shift.md`: Shiftキー制御
- `android-candidate-scroll.md`: 候補スクロールの挙動
- `android-english-enter.md`: 英字Enter確定の修正
- `android-keyboard-ui-stability.md`: キーボードUIの安定化
- `windows-inference-memory.md`: Windows推論時のメモリ・GPU設定
