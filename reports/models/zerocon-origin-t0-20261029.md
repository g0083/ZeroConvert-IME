# ZeroCon-Origin タグ統合モデルの接続試作

2026-10-29。**学習系の初期実装と極小モデルの接続確認まで実施。配布品質のモデルは未完成。**
[構築計画](zerocon-origin-build-plan-20261029.md)のP0〜P7をすべて完了した報告ではない。
P2/T0の一部を自作fixtureで先行検証した。T0の1M〜5M処理tokensという予算にも未到達。

## 結果

外部の初期重み・語彙を使わず、同じ524,928パラメータのモデルをランダム初期化から学習した。
`<task_convert>`、`<task_repair_qwerty>`、`<task_predict>`で表記変換・読み修復・先読みsuffixを切り替え、
`<keep>`と`<abstain>`も学習する。Flickは契約のみで、学習していない。

| partition | かな漢字変換 | 読み修復・keep・abstain | 先読み・abstain | 合計 |
|---|---:|---:|---:|---:|
| 学習元文・過学習の確認 | 16/16 | 48/48 | 32/32 | 96/96 |
| 異なる元文・小規模validation | 0/4 | 2/12 | 4/8 | 6/24 |

validationの正解を機能別の汎化精度と解釈しない。先読みの正解4件はすべて無提案例。
変換は未見元文で成功しておらず、実用モデルとしての性能は成立していない。
keep/abstainの内訳、誤出力、全ケースは`artifacts/results/origin-t0-a0-20261029-r3-parity.json`に保存した。

GGUF F16とHFの出力は**120/120例で一致**。
これは整数トークンIDを渡す契約での比較であり、ランタイムの生文字列tokenizerの全面互換ではない。
既存CPUランタイムb4846は`parse_special=false`を無視するため、リテラルタグはOriginCodecで符号化してIDで渡す。
U+2581にも明示的なbyte fallback処理が必要。
空白、全角数字、改行、結合文字、異体字、絵文字、リテラルタグを検査した。

## 学習・来歴

- データはこの実装のための自作合成20文書から120例を展開。16文書/96例をtrain、4文書/24例をvalidationへ固定。
- 既存評価例・個人履歴・外部教師・既存v4の817行を学習へ取り込んでいない。
- 語彙はtrain行と自作ASCII/空白補助文だけからSentencePiece BPE 1,024 piecesを作成。
- Llama互換Dense、2層、幅128、Q/KV heads=4/2、FFN384、tied embedding、context256。
- RTX 4070、PyTorch 2.6.0+cu124、Transformers 5.20.2、SentencePiece 0.2.2、safetensors 0.8.0。
- 全パラメータ更新、CEのみ。seed42、500steps、batch16、LR0.001、warmup3%、cosine、AdamW、BF16 autocast。
- 処理130,277 tokens、target17,347 tokens。unique元文がこの量あるという意味ではない。
- 純学習ループ6.06秒、約21,494処理tokens/s。0.525Mの極小モデルの値をN33/S68へ外挿しない。
- 損失6.95561→0.005260。変換validation CEは6.94749→7.25649へ悪化しており、過学習を示す。
- 250stepから再開した実行は、連続500stepと損失履歴・全モデルtensorが完全一致。

元のfixtureを点検し、「ん」の打鍵を完成状態が明確な`nn`へ統一した。
この検査はPythonの合成ローマ字変換との往復で、Windowsの実打鍵確認の代用ではない。
実イベント、カーソル移動、削除、取消、同義ローマ字、保護文字列の生成品質は今後の検証対象。

自作コード・fixture・試作重みの配布方針はApache-2.0。
依存実装は[Transformers](https://github.com/huggingface/transformers/blob/main/LICENSE)、
[SentencePiece 0.2.2](https://github.com/google/sentencepiece/blob/v0.2.2/LICENSE)等の個別条件を保持する。
Apache本文、PyTorchのLICENSE/NOTICE、採用実装のLICENSEを成果物へ同梱した。
source台帳の承認記録は機械的な入口検査であり、将来の第三者資料の利用条件確認を代行しない。

既存`standard-v3-knowledge/manifest.json`は分割・hash・817/85行を記録しているが、
そのファイルには行単位の著作者・利用条件・教師の権利根拠がない。
今回は自動でOriginへ移さず、既存データを保持した。追加の来歴資料を確認できれば再利用を判断できる。

## 成果物と検証

- 学習：`artifacts/checkpoints/origin-t0-a0-20261029-r3/`
- 再開検証：`artifacts/checkpoints/origin-t0-resume-20261029-r3/`
- GGUF：`artifacts/models/origin-t0-a0-20261029-r3-export/origin-f16.gguf`
- GGUF容量：1,076,960 bytes（約1.03 MiB）。SHA-256：`d6d8cf0975ca695b32de8ee68bd7c933dd690e5afcd07c569d4fc9e825c7d98a`
- データ：`artifacts/datasets/origin-fixture-20261029-r2/`
- 結果：`artifacts/results/origin-t0-a0-20261029-r3-parity.json`、`origin-t0-resume-20261029-r3.json`
- 学習/データ/語彙のhashはrun manifest、変換器と変換module群・元HFのhashはexport manifest、CPU実行器hashはparity結果に保存。

再現コマンドとデータ形式は[学習手順](../../docs/origin-training.md)を参照。
`training/test_origin.py`の9件が成功。文字保持・制御タグ混入防止・EOS切断拒否・入力状態・
元文/document分離・承認不足/教師来歴不足の拒否・パラメータ数・損失mask/shift/長さ正規化を確認した。
GGUF化とCPUの専用サーバーによる全例比較も成功。サンドボックスがlocalhost通信を拒否したため、
許可された制限外の実行で検証した。サーバーは検証専用に起動し、所有processだけを終了した。

## 続く工程

1. P0の現行3タスク基準・実行器とモデルhash・応答指標・製品合格条件を固定する。今回の試作評価で代用しない。
2. P1の来歴・読み校正済みコーパスと独立した封印評価を用意し、近重複・出典・教師生成単位の隔離を実装する。
3. 8k/16k語彙を作成し、N33/S68で50M tokens比較を行う。本学習、入力状態の拡充、教師条件比較へ進む。
4. 誤補正・保護領域・先読み妥当性・CPU/Vulkan遅延を評価する。合格後にRust adapter・量子化・実IMEの回帰へ進む。

P0全体、承認コーパス収集、本学習、外部教師蒸留、Rust/Swift移行、量子化、実IMEへの適用、
入力アプリでの確認、インストーラー、commit/pushは未実施。ブランチを切り替えず、既存変更・重み・設定を保持した。
