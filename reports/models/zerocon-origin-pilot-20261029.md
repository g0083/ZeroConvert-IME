# ZeroCon-Origin N33 pilot 学習・継続記録

2026-10-29の学習開始時点の記録。後続の学習完了・未達結果・入力状態の偏りは
[N33 50M検証](zerocon-origin-n33-50m-20261029.md)を参照。以下の開始時の実行ハンドルは終了済み。
前の作業は[T0試作](zerocon-origin-t0-20261029.md)として実モデル・検証結果が残る進捗だった。
今回は、その96例の過学習確認を合格基準にせず、実際のコーパスとN33へ進めた。

## 採用した元文と来歴

- 公開合成日本語：[OysterCoreAI/SFT-General-Japanese-60K](https://huggingface.co/datasets/OysterCoreAI/SFT-General-Japanese-60K)、60,000会話、約154MB。
- 採用revision：`fdab76b7d780806e98bd1006a06c7444bb8e936a`。Apache-2.0本文とATTRIBUTIONを保持した。
- 上流は[LLM-jp magpie-sft-v1.0](https://huggingface.co/datasets/llm-jp/magpie-sft-v1.0)。公式カードはApache-2.0で、CALM3が質問、Qwen2.5-32B-Instructが応答を生成したと明記する。
- 両生成元のモデルカードも確認し、確認したrevision・声明・取得物のhashをacquisition manifestへ保存。
- 元の生成時checkpoint revisionは公開元に記載がない。現在確認したrevisionと混同しない。
- 派生データのカードが案内するauditファイルは、固定した実リポジトリに無かった。公開元の品質数値を独立検証済みとして採用していない。
- 元データをApacheで公開するという声明に基づく研究用採用。各生成文の第三者権利や事実の正確さを一律保証するものではない。

全ファイルと上流・生成元の根拠は`artifacts/datasets/origin-magpie-source-20261029/`。
この取得は公開データと文書のみで、外部モデル重みやリモートコードを実行していない。
既存の817/85行、v4の重み、個人履歴も取り込んでいない。

## データ隔離と読み

読み付与はfugashi 1.5.2とunidic-lite 1.0.8のUniDic 2.1.2。辞書はBSDを選び、COPYING/BSDを保持した。
発音ではなく書字形の`kana`を用いる。助詞「は・へ・を」、長母音の綴り、数字の幅、空白、ASCIIを保持する。
未知漢字・対応不整合508件はレビューキューへ隔離した。すべての読みを人が校正したわけではない。

6,128文書をタスク生成より先に取り分け、84,178個の元文キーを予約した。
この予約文書の全文や内容をモデル・語彙へ渡していない。監査はIDと文の一致検査のみ。
最終評価の正解・許容解はまだ付けていないため、「封印評価に合格した」とは扱わない。
OSのアクセス制御を設けた隔離でもない。

| partition | 文書数 | 変換 | 読み修復/keep/abstain | 先読み/abstain | 合計 |
|---|---:|---:|---:|---:|---:|
| train | 12,123 | 182,567 | 299,518 | 162,822 | 644,907 |
| validation | 722 | 10,915 | 17,959 | 9,718 | 38,592 |

元文10万件を上限にしたpilotで、60,000会話をすべて学習した量ではない。
同じ元文からの派生例は同じ文書partitionへ置く。近重複は文字5-gramの候補検索とJaccard .85で除去する。
完全一致の元文・タスク入力/正解・文書ID・既存5評価キー・予約文書との一致を独立のstream監査でも検査し、PASS。
候補検索の見逃し、意味的な類似、共有テンプレートの完全除去までは証明していない。
`origin-magpie-pilot-20261029-r1`は予約方法の修正前なので学習に使わない。

## 比較条件と開始した学習

過去のv4 CPU結果について、5モデルの実在重み、CPU実行器、両評価ファイルのhash、
ケース数・ID・正解集計を照合した。最高のStandard v4 Q8は459/480。
`artifacts/results/origin-comparison-contract-20261029-r2.json`へ計画書の条件を固定した。
Standard 468/480以上、Nano 466/480以上、未見評価のpaired差、誤補正・先読み・速度・保護・実IMEの条件を維持する。
この固定は新しいCPU測定やP0全体の完了ではない。

開始したrunは`artifacts/checkpoints/origin-n33-t1-50m-20261029-r1/`。
実行ハンドルは統合exec session `12457`。再開前にこのハンドルを確認し、生存中なら別runを重複起動しない。
`progress.json`やロックだけを生存の証明にしない。

- N33：32,514,560パラメータ。8層、幅512、Q/KV heads=8/2、FFN1536、共有埋め込み。
- trainのみからSentencePiece BPEの16,384 piecesを作成。既存v4の語彙・重みを引き継がない。
- 外部合成元文を使うため、ランダム初期化でも`Origin-B/sequence-CE`と記録。
- 50,000,000処理tokensのT1比較予算。固定サンプリングから17,579 optimizer stepsを算出。
- batch16、gradient accumulation4、sequence上限256、LR3e-4、AdamW、BF16 autocast、seed42。
- convert:repair:predict=45:30:20を正規化してサンプリング。自然文LM taskは未導入。
- validationは38,592例のうち固定seedの512例をCE診断に使う。500stepsごとに記録、1000stepsごとにcheckpoint。

最初の500/1000stepの変換validation CEは6.1819→5.2373、修復は1.7567→1.6200、先読みは0.8037→0.6552。
これは途中の学習信号であり、正解率でもv4との品質比較でもない。
最初の約280万処理tokens時点のスループットは約14.6k tokens/s。純粋な製品推論速度には使わない。
進捗・失敗・最終metricsはrunフォルダと`artifacts/logs/origin-n33-t1-50m-20261029-r1.log`を正本とする。

## 検証・次の行動

現在の回帰テストはOrigin契約/損失/予算10件、読み/近重複4件、既存採点器adapter2件が成功。
gradient accumulation導入後も再開の重み・損失一致を検査した。生のCPU比較はGPU学習終了後に行う。
読みは自動ラベル、補正ノイズは合成なので、実IMEの品質・実打鍵の救済率とは区別する。

1. 生存するN33学習を同じハンドルで追跡し、停止・失敗なら実際の状態を確認して原因を直す。
2. 学習後にGGUF化し、HF/CPU整合、3タスクvalidation、既存480問を同じ採点器・CPU条件で比較する。
3. 同じ語彙・元文と50M予算のS68を順次比較し、データ/損失/教師条件の次の実験を選ぶ。
4. 変換だけでなく、誤補正、保護文字列、先読み妥当性、遅延、独立評価、Windowsの操作回帰でv4超えを検証する。

現時点でv4超えは未達。既存IME・モデル・ユーザー設定を維持した。
Windowsビルド、実IME適用、入力アプリ確認、インストーラー、commit/pushは行っていない。
