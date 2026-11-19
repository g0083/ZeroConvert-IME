# Android移植進捗・監査記録 (`reports/port-history/`)

Windows 版 azooKey から Android 版 ZeroConvert-IME への移植過程で作成された、各ステージの作業記録および監査・復旧レポート群です。

> [!NOTE]
> **構成変更について**: 2026-11-08 に旧 `:app` Gradle モジュールおよび Android 独自の学習ストアは廃止され、現在は Windows 共通エンジンをコンパイルする `:rebuilt` に一本化されています。現在の Android 構成の正本は [docs/android-shared-engine.md](../../docs/android-shared-engine.md) および [docs/android-development.md](../../docs/android-development.md) を参照してください。

## 移植進捗・監査・比較

- [android-windows-port-progress.md](android-windows-port-progress.md): 移植全体の進捗サマリー
- [android-port-completion-audit.md](android-port-completion-audit.md): 75 項目の移植完了監査記録
- [windows-android-conversion-comparison.md](windows-android-conversion-comparison.md): 変換ロジックの比較
- [windows-android-feature-gap.md](windows-android-feature-gap.md): 移植初期の機能ギャップ一覧
- [android-shared-engine-validation.md](android-shared-engine-validation.md): 共通エンジン初期統合時の検証記録
- [windows-shared-performance-validation.md](windows-shared-performance-validation.md): 共通エンジン最適化時の Windows 適用記録
- [windows-source-recovery.md](windows-source-recovery.md): 2026-10-22〜23 ソース巻き戻り復旧記録

## ステージ別移植記録（歴史的記録）

- [android-windows-port-stage2.md](android-windows-port-stage2.md): Stage 2（JNI・モデル・先読み/補正基盤）
- [android-windows-port-stage3.md](android-windows-port-stage3.md): Stage 3（自動補正・候補統合）
- [android-windows-port-stage4.md](android-windows-port-stage4.md): Stage 4（Windows タイポ規則・語彙移植）
- [android-windows-port-stage5.md](android-windows-port-stage5.md): Stage 5（範囲変換UI試作 ※後続で取り下げ済み）
- [android-windows-port-stage6.md](android-windows-port-stage6.md): Stage 6（自動確定巻き戻し・検証基盤）
- [android-windows-port-stage7.md](android-windows-port-stage7.md): Stage 7
- [android-windows-port-stage8.md](android-windows-port-stage8.md): Stage 8（通常辞書補正・探索・ユーザー辞書）
- [android-windows-port-stage9.md](android-windows-port-stage9.md): Stage 9（旧選択上書き学習 ※後続で共有 Swift へ統合）
- [android-windows-port-stage10.md](android-windows-port-stage10.md): Stage 10（旧形態素頻度バイナリ ※後続で廃止）
