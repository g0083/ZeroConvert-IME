# タイポ補正・先読み検証レポート (`reports/typo-assist/`)

タイポ補正、キーボードハイブリッド、二段先読みアルゴリズムの検証・評価レポート群です。

- `corrector-standard-qwerty-ranker-20261103.md`: Standard v4候補順位付け、prospectiveイベント評価、実打鍵収集ツール、CPU遅延と実IME適用の判断

## 主なレポート

- `corrector-qwerty-repair-loss-20261030.md`: 修復差分CE・コピー対照損失の同条件学習、自己コピーの減少と許可外編集への移行、正常保持・長文回帰の不採用判断
- `corrector-qwerty-full-vs-local-20261030.md`: Nano v4からの全文生成/局所指示の同条件学習、長入力増補、不正指示・量子化・遅延の比較、局所方式の不採用判断
- `corrector-qwerty-reading-search-20261030.md`: Nano v4による物理キー候補の読み採点、表記生成の追加経路、固定testと長めの新作文章での改善・誤提案・遅延
- `corrector-qwerty-state-and-channel-20261030.md`: キー列・編集状態の同条件学習、物理キーによる候補検査、新作20文章を含む比較と汎化の課題
- `corrector-qwerty-v4-20261030.md`: native QWERTY打鍵データ、DoRA・正常保持・重み補間、固定ポリシー評価と旧回帰の退行、QWERTY専用候補
- `corrector-v4-advanced-and-benchmark-audit-20261030.md`: 通常v4の学習手法を用いたcorrector追加学習と、評価内容・辞書制約・正解oracleの監査
- `corrector-v4-nano-20261030.md`: Nano v4ベースのCorrector v4作成・データ監査・量子化・旧版比較
- `keyboard-hybrid-ime-20261028.md`: 実IMEへのキーボードハイブリッド統合と検証記録
- `keyboard-hybrid-20261027.md`: キーボード事前分布を用いたハイブリッド補正の検証
- `corrector-ablation-20261027.md`: 補正モデルのアブレーション評価
- `corrector-size-comparison-20261027.md`: 補正モデルサイズ別（xsmall / small等）の比較評価
- `typo-improvement-20261027.md`: タイポ補正精度の改善実験
- `benchmark-audit-20261027.md`: ベンチマーク評価の監査記録
- `Windows_Predictive_Typo_Implementation_Study.md`: タイポ補正・先読み実装の初期検討調査
