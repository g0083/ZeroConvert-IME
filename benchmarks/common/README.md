# 共通ベンチマーク (canonical)

Windows (`windows/frontend/src/data/`, `src-tauri`) と Android (`android/app/src/main/assets/`) の
ベンチマークを同一内容にするための正本。生成物は手編集せず、同期スクリプトで作る。

## ファイル

| ファイル | 内容 | 件数 | 層 |
| --- | --- | --- | --- |
| `conversion.jsonl` | かな漢字変換（基本230/高難度250/実IME160/口語100） | 740 | モデル単体＋実IMEの入口 |
| `typo.jsonl` | タイポ補正（従来300＋ clean 増強150） | 450 | 補正ポリシー |
| `predictive.jsonl` | 先読み継続 | 30 | 補正/先読み |
| `parity_cases.jsonl` | 採点の共通期待値（Windows/Android/Rust の照合用） | 固定 | 採点契約 |

件数は `canonical-v1` のスナップショット。件数文字列は README ではなく生成時マニフェスト
`manifest.json` を正として表示する。

## スキーマ

### conversion.jsonl

1 行 = 1 問。`id` は 1..740。先頭 480 は従来の `basic/hard` と同じ順序・ID を保持し、
後段に `realistic`（481..640）、`colloquial`（641..740）を続ける。

| フィールド | 型 | 説明 |
| --- | --- | --- |
| `id` | int | 通し番号 |
| `subset` | string | `basic`/`hard`/`realistic`/`colloquial` |
| `subset_label` | string | 表示用（件数入り） |
| `category` | string | 元データのカテゴリ |
| `left_context` | string | 左文脈。`なし` は文脈なしとして保持 |
| `target` | string | ひらがなの読み（入力） |
| `expected` | string | 正解表記 |
| `acceptable` | string[] | 別解（許容表記） |
| `forbidden_examples` | string[] | 正解に含んではいけない誤表記。含む出力は即 NG |
| `focus` | string | 誤変換抑止の焦点語。なければ空 |
| `origin` | string | 生成元ファイル |
| `input_mode` | string | `kana`（読み入力） |
| `expected_action` | string | `convert` |

### typo.jsonl

1 行 = 1 問。`id` 1..300 は従来 300（typo 255 + clean 45）、301..450 は clean 増強（150）。

| フィールド | 型 | 説明 |
| --- | --- | --- |
| `id` | int | 通し番号 |
| `category` | string | 従来は `business`/`daily`/`tech`/`general`、増強分は元変換カテゴリ |
| `typo_type` | string | `missing_vowel`/`nasal`/`adjacent`/`prolonged`/`geminate`/`transposition`/`flick`/`clean` |
| `typo_type_label` | string | 日本語表示 |
| `is_clean` | bool | 正常入力（保持対象）かどうか |
| `context` | string | 左文脈 |
| `input_reading` | string | カタカナの入力読み（typo は壊れた読み） |
| `target_reading` | string | ひらがなの正しい読み |
| `expected` | string | 正解表記 |
| `acceptable` | string[] | 別解 |
| `raw_romaji`/`raw_input` | string | ローダ互換の冗長入力。`raw_input == input_reading` |
| `input_mode` | string | `qwerty`（従来 `adjacent`）/`flick`（従来 `flick`）/それ以外は `kana` |
| `origin` | string | `legacy300` / `derived-clean` |
| `expected_action` | string | `repair`（typo）/ `preserve`（clean） |
| `source_subset` | string | 増強分の元 subset（`""` は従来） |

clean 増強 150 の内訳: `colloquial` 100 問すべて（過剰漢字抑止の最重要）＋
`realistic` から 50 問（カテゴリ横断の均等抽出）。変換集合の正しい読みを
`is_clean=true` の保持対象として再利用し、タイポでない入力を補正しないことを測る。
学習データとの分離は [dataset-isolation](../../docs/dataset-isolation.md) に従う。

### predictive.jsonl

従来どおり `{id, category, context, partial_input, expected_continuation,
acceptable_continuations, full_sentence}` に `origin` を加える。

## 実行と計測

設定画面から実行すると、選択中のモデルをベンチマーク専用セッションに読み込む。
両版は同じ設定の llama.cpp コンテキストを使い、1回のウォームアップ後に入力順で逐次推論する。
共通 Rust が入力整形、NAM の処理、採点を行い、画面は16問ごとに結果を受け取る。

- かな漢字変換の「モデル推論」は既定の計測範囲で、モデルの直接生成を測る。
- 「共通候補生成」は同じ740問と採点ルールで、共有の Swift 変換エンジンが標準辞書を検索し、モデル候補に N-best 候補を追加して順位付けするまでを測る。学習・ユーザー辞書・予測は無効で、各問で未確定入力をリセットする。N-best 追加検索の平均時間も表示する。
- タイポ補正は直接生成と共通の辞書・規則による補正を測る。
- 先読みは事前に取得した標準辞書の逆引きと、読みの延長および延長後の表記への変換を測る。
- 問題ごとの遅延には共通処理と推論を含む。セッション準備から解放までの実行時間には、画面と推論処理間の転送も含む。
- Windows は設定中の CPU / Vulkan、Android は CPU を使う。同条件比較は同じ GGUF と辞書を使い、Windows も CPU を選ぶ。

## 採点契約（共通エンジン）

正本は Rust `windows/crates/server/src/ai_assist/benchmark.rs`（`engine/rust` から
直接コンパイル）。Windows Tauri と Android は同じ判定関数で判定し、結果を同一にする。

- **変換**: forbidden 含有 → NG。完全一致、空白除去一致、ひらがな一致（`acceptable` も同様）を正解。
- **タイポ typo（`repair`）**: 合成結果（補正がなければメイン変換）を `expected`/`acceptable` の
  完全/空白一致 → `exact_match`、部分包含 → `variant_match`、それ以外 `missed`。
  さらに補正単独の正答を別記録にする（合算正解と単独を混同しない）。
- **タイポ clean（`preserve`）**: 補正が無い／`expected`／メイン出力／`input_reading`（空白除去）
  のいずれかなら `clean_preserved`、それ以外の他語への改変は `false_positive`。
- **先読み**: 完全文が `full_sentence` と一致、または `expected_continuation` / 別解で終わる → 正解。
- **遅延**: 平均だけでなく p50/p95/p99 を反復測定。通信失敗・無提案は別件数。

## 同期

```sh
python scripts/run.py benchmarks/common/build_common_benchmark.py --out-dir benchmarks/common
node scripts/sync_benchmarks.mjs --check
```

旧 `benchmark480.json` / `typo_benchmark300.json` は互換維持のために
`benchmark740.json` / `typo_benchmark450.json` と並存させる。UI は新規ファイルを正とする。

2016 起源の研究ベンチ（`benchmarks/research/`、`benchmarks/keyboard-correction/`）や
Windows RPC 確認ツールは本正本の対象外。公開精度の代表値としては使わない
（[corrector-evaluation](../../docs/corrector-evaluation.md) の見解を維持）。
