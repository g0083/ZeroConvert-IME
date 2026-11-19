# タイポ補正・先読み・翻訳検証レポート (`reports/typo-assist/`)

タイポ補正、キーボードハイブリッド、先読み、翻訳入力の検証・評価レポート群です。現行本番仕様は [docs/typo-correction.md](../../docs/typo-correction.md)・[docs/predictive-assist.md](../../docs/predictive-assist.md)・[docs/investigations/windows-translation-input.md](../../docs/investigations/windows-translation-input.md) を参照してください。

## 現行統合アーキテクチャ・翻訳検証（v0.2.3 以降）

- [shared-keyboard-correction-20261114.md](shared-keyboard-correction-20261114.md): 共通エンジンの辞書補正と Android タッチ位置重み付けの検証
- [unified-converter-corrector-20261104.md](unified-converter-corrector-20261104.md): メイン変換モデル統合によるタイポ補正刷新（旧 18090 番 Corrector 廃止）
- [windows-translation-realtime-fix-20261105.md](windows-translation-realtime-fix-20261105.md): Windows 翻訳入力のリアルタイム反映修正と実 IME 検証
- [windows-selection-translation-fix-20261105.md](windows-selection-translation-fix-20261105.md): Windows 選択翻訳ポップアップの表示・DLL 混在修正の検証
- [windows-translation-keepalive-20261105.md](windows-translation-keepalive-20261105.md): Windows 翻訳サーバー常時起動設定の検証
- [windows-translation-before-realtime-fix-20261105.md](windows-translation-before-realtime-fix-20261105.md): リアルタイム翻訳修正前の検証記録（アーカイブ）

## 研究実験・旧パイプライン記録（2026-10）

> [!NOTE]
> 以下は独立 Corrector モデル（旧 18090 番）やオフライン QWERTY 候補順位付け・局所修復研究の記録です。現行 IME にはメイン変換モデル統合方式が採用されています。

- [corrector-standard-qwerty-ranker-20261103.md](corrector-standard-qwerty-ranker-20261103.md): Standard v4 候補順位付け、prospective イベント評価、実打鍵収集ツール
- [corrector-qwerty-repair-loss-20261030.md](corrector-qwerty-repair-loss-20261030.md): 修復差分 CE・コピー対照損失の比較と不採用判断
- [corrector-qwerty-full-vs-local-20261030.md](corrector-qwerty-full-vs-local-20261030.md): Nano v4 からの全文生成/局所指示の比較と局所方式の不採用判断
- [corrector-qwerty-reading-search-20261030.md](corrector-qwerty-reading-search-20261030.md): Nano v4 による物理キー候補の読み採点・追加経路実験
- [corrector-qwerty-state-and-channel-20261030.md](corrector-qwerty-state-and-channel-20261030.md): キー列・編集状態の同条件学習と物理キー候補検査
- [corrector-qwerty-v4-20261030.md](corrector-qwerty-v4-20261030.md): native QWERTY 打鍵データを用いた専用 corrector 実験
- [corrector-v4-advanced-and-benchmark-audit-20261030.md](corrector-v4-advanced-and-benchmark-audit-20261030.md): corrector 追加学習とベンチマーク監査
- [corrector-v4-nano-20261030.md](corrector-v4-nano-20261030.md): Nano v4 ベースの独立 Corrector v4 作成・比較
- [keyboard-hybrid-ime-20261028.md](keyboard-hybrid-ime-20261028.md): キーボードハイブリッド初版の実 IME 統合記録（旧専用モデル版）
- [keyboard-hybrid-20261027.md](keyboard-hybrid-20261027.md): キーボード事前分布を用いたハイブリッド補正の初期検証
- [corrector-ablation-20261027.md](corrector-ablation-20261027.md): 補正モデルのアブレーション評価
- [corrector-size-comparison-20261027.md](corrector-size-comparison-20261027.md): 補正モデルサイズ別（xsmall / small 等）の比較評価
- [typo-improvement-20261027.md](typo-improvement-20261027.md): タイポ補正精度の改善実験
- [benchmark-audit-20261027.md](benchmark-audit-20261027.md): ベンチマーク評価の監査記録
- [Windows_Predictive_Typo_Implementation_Study.md](Windows_Predictive_Typo_Implementation_Study.md): 初期の実装方式調査（2026-10-21、旧 b4846 / LFM 検討時）
