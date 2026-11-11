# Windows環境の変換ロジック

本ドキュメントは、ZeroConvert-IME の Windows 版における「入力 → 変換 → 表示 → 確定」までの処理を、層ごとに解説する。対象は `windows/` 配下の Rust クライアント/サーバー、Swift 変換エンジン、Tauri 設定 UI である。Android 実装は対象外。

関連ドキュメント: [architecture.md](architecture.md)（構成・コマンド）、[file-map.md](file-map.md)（探索先）、[typo-correction.md](typo-correction.md)（タイポ補正詳細）、[predictive-assist.md](predictive-assist.md)（先読み詳細）。

---

## 1. 全体構成

### 1.1 コンポーネント

| コンポーネント | 役割 | 主なファイル |
|---|---|---|
| TSF クライアント DLL | 入力アプリに組み込まれる IME 本体。キーイベント受信、composition 状態管理、IPC 呼び出し、表示 | `windows/crates/client/src/engine/composition.rs` |
| 変換サーバー | composition の正規所有者。Swift FFI 呼び出し、候補生成、AI アシスト、学習 | `windows/crates/server/src/main.rs` |
| Swift 変換エンジン | 辞書 + Zenzai (GGUF) によるかな漢字変換、文脈・設定適用 | `windows/server-swift/Sources/azookey-server/azookey_server.swift` |
| 候補 UI | 候補一覧・読み表示・入力モード表示 | `windows/crates/ui/` |
| launcher | サーバー・UI・モデルバックエンドの起動/監視 | `windows/crates/launcher/src/main.rs` |
| 設定 UI (Tauri) | 設定保存・モデル切替・ベンチマーク | `windows/frontend/` |
| 共通定義 | IPC 契約 (protobuf)、設定、候補 ID、ローマ字規則 | `windows/crates/shared/` |

### 1.2 データフロー

```
入力先アプリ
  → client DLL (TSF キーイベント・composition 状態)
  → engine/ipc_service.rs
  → セッション別 Named Pipe 上の Tonic RPC
  → server/main.rs (composition 所有者・操作順序管理)
  → C ABI → Swift azookey_server.swift
  → KanaKanjiConverterModule (辞書 + Zenzai / zenz.gguf)
  → 通常変換候補
  → Rust ai_assist で追加候補を生成
  → client へ応答 → TSF composition 更新 / ui.exe へ表示
  → 確定通知 → 通常変換の学習 または AI アシスト専用の記録
```

Pipe 名は `\\.\pipe\LOCAL\azookey_{server,ui,launcher}_s<sessionId>`（`shared/src/lib.rs`）。セッション ID はハードコードしない。

### 1.3 重要な境界

- **クライアント予測状態** と **サーバーの正規状態** は混同しない。`CompositionOwner` が操作元接続と composition 所有権を管理する。
- **通常変換**（Swift）、**Rust の「もしかして」**（タイポ補正）、**先読み**（予測変換）は別機能・別モデル用途。
- 入力・TSF コールバック・候補更新経路には待機・同期 I/O・無制限処理を入れない。

---

## 2. 入力処理の基本

### 2.1 キーイベント入口

`composition.rs` の `handle_key` / `handle_key_up` が TSF キーイベントを受け取る。修飾キー状態（Shift/Ctrl/Alt/Win）を `ModifierState` として保持し、ローマ字→かな変換、直接入力、特殊キー（Space/Enter/Backspace/Delete/矢印/Tab/Esc）を振り分ける。

### 2.2 ローマ字→かな

ローマ字テーブル（`romaji_lookup.rs`、`shared` の `RomajiRule`）で入力列をかなに変換する。未確定ローマ字（子音だけ残っている状態）を `raw_input` として保持し、`raw_hiragana` と区別する。削除時は Swift の正規打鍵列を使う（raw 入力の 1 文字削除と仮定しない）。

### 2.3 composition 状態

`CompositionState`（Composing / など）と、`raw_input` / `raw_hiragana` / `preview`（変換結果表記）/ `suffix`（未変換尾部）/ `corresponding_count` / `selection_index`（文節選択）/ `candidates` を一組で管理する。clause スナップショット（文節確定履歴）も保持する。

### 2.4 入力ポリシーの正本

句読点の組成/正規化、数字の組成、翻訳モードの literal 扱い、記号の幅既定値は `windows/crates/shared/src/conversion_policy.rs` が正本である（`CHARACTER_WIDTH_SYMBOL_DEFAULTS` もこのファイルに移し、`shared` から再公開している）。

- 句読点は既定設定 `punctuation_commit = false` で composition に加わる。`,` `.` `?` `!` を `、` `。` `？` `！` として読みに載せる判断は `punctuation_for_reading` が持つ。
- 数字は `UserAction::Number` → `AppendText` として読みに加わる。
- 記号の幅既定値は `symbol_default_fullwidth` と `CHARACTER_WIDTH_SYMBOL_DEFAULTS` の組で決まり、`full_width.rs` はその定数から表を組む。
- 設定ON時の `punctuation_commit` 分岐は Windows 固有で、共有仕様の対象外。

仕様は [tests/input-policy/cases.tsv](../../tests/input-policy/cases.tsv) にあり、Android と共有している。変更時はこの TSV を先に更新し、`cargo test -p shared --offline` と `scripts/check-input-policy-parity.ps1` を通す。

---

## 3. 通常変換 (かな漢字変換)

### 3.1 RPC 経路

クライアントは `AppendText` / `ReplaceComposition` / `RemoveText` / `MoveCursor` / `ShrinkText` などの RPC をサーバーに送る。サーバーは Swift FFI を呼び出し、`ComposingText { hiragana, suggestions }` を返す。

主な RPC（`shared/service.proto`）:

| RPC | 用途 |
|---|---|
| `AppendText` | 文字追加入力 |
| `ReplaceComposition` | composition 全体置換（編集後再構築） |
| `RemoveText` | 1 文字削除 |
| `MoveCursor` | カーソル移動 |
| `ShrinkText` | 文節確定（先頭部分を確定） |
| `AdvanceClause` / `PrepareFutureClauses` | 文節ナビゲーション |
| `AdjustClauseBoundary` | 文節境界調整 |
| `StartReconversion` | 再変換（確定済み文字列から読みを推定） |
| `ClearText` | composition クリア |
| `SetContext` | 左文脈設定 |
| `CommitLearningCandidate(s)` | 学習コミット |

### 3.2 Swift 側の変換

`azookey_server.swift` の `getOptions` / `confidentKeyboardTypoCorrectionForZenzaiMerge` が辞書 + Zenzai (zenz.gguf) を呼び出す。`experimental_typo_correction`（Swift 側キーボードタイポ補正）は `general.experimental_typo_correction` から渡される。これは Rust 側 AI アシストとは別経路。

### 3.3 候補 ID

- 通常候補: Swift が付与する ID
- 予約 ID（`shared::ai_candidate`）:
  - `999901` = TYPO_CORRECTION（補正）
  - `999902` = PREDICTIVE（先読み）
  - `999903` = DYNAMIC_MACRO（動的マクロ: 日時・計算など）

予約 ID はサーバーで処理し、Swift の通常候補学習には渡さない。

### 3.4 ローカルマクロ

`append_local_macros` が決定的な展開（日付・計算など）を即時追加する。`ai_assist::current_expansions` を使用。候補 ID は `DYNAMIC_MACRO`。

---

## 4. ライブ変換

入力中にリアルタイムで変換結果（preview）を composition に表示する。`live_conversion_reading` / `live_conversion_reading_update` が読み（ひらがな）を生成し、Swift 変換で表記を得る。vertical adjustment（読み表示の縦位置）も管理する。

ライブ変換の安定性履歴（`live_conversion_history: VecDeque<String>`）とスナップショット（`live_conversion_snapshots`）を保持し、自動確定の判定に使う。

---

## 5. 自動確定 (Auto-commit)

ライブ変換の先頭部分が十分に安定したら、サーバー側で `ShrinkText` 相当を実行し、先頭部分を確定して composition を縮める。クライアントの `maybe_auto_commit_live_conversion_chunk` が判定する。

### 5.1 判定条件 (`find_auto_commit_live_conversion_snapshot`)

- 全体の読みが 16 文字以上 (`MIN_TOTAL_HIRAGANA = 16`)
- 確定部分が 8 文字以上 (`MIN_COMMIT_HIRAGANA = 8`)
- 残りが 7 文字以上 (`MIN_REMAINING_HIRAGANA = 7`)
- 表記接頭部が 4 文字以上 (`MIN_PREFIX_CHARS = 4`)
- 安定性履歴が 5 段階 (`STABLE_STEPS = 5`)
- suffix が空、未確定ローマ字尾部がない
- clause スナップショット / future clause が空、selection_index == 0
- fixed_prefix が空、temporary_latin でない、reconversion 中でない
- `typo.hold_auto_commit` が false、`jev_conversion` が false

さらに、スナップショット候補の preview が現在 preview の接頭部であり、履歴の全エントリが `snap.preview` で始まり長さが超えること。最大 hiragana_len のスナップショットを採用する。

### 5.2 確定テキスト算出

`auto_commit_committed_text`: remaining_text が current_preview の接尾辞なら、差分を確定テキストとする（TSF 合成境界ズレ防止）。そうでなければ snapshot_prefix を使う。

### 5.3 補正適用後の扱い

補正適用直後は自動確定の安定性履歴を作り直す（`typo.hold_auto_commit` で一時停止）。

自動確定を挟まない続き入力でも、左文脈から確定済みの読み・表記を逆変換して同じ境界考慮の再変換を行う自動継続があり、詳細は [境界文脈を保持する再変換](investigations/boundary-context-reconversion.md) に記載している。

---

## 6. タイポ補正 (Rust AI アシスト)

`general.experimental_ai_assist` が有効なとき、Rust 側の補正・先読みが動作する。Swift 側 `experimental_typo_correction` とは別経路。

### 6.1 表示と操作

| 状態 | 本文 | フローティング |
|---|---|---|
| 補正不要・通常先頭候補と一致 | 通常のライブ変換 | 原文のみ |
| 補正の提案 | 通常のライブ変換 | 変更前 → 変更後、Tab で補正 |
| 根拠の強い補正・Tab 採用済み | 補正後の未確定文字列 | 補正済み、Esc で戻す |

- Tab で補正しても確定せず、続けて入力できる。先読みの Tab は従来どおり採用・確定。
- 補正適用直後の Esc は原文の打鍵履歴と表示を復元。同じ入力状態では取り消した候補を再表示しない。
- Enter は本文の表示を確定し、未採用の提案を自動採用しない。
- Space で開く候補一覧には補正候補を 2 番目に「補正」表示で含める。通常候補の相対順と ID は維持。
- Backspace/Delete は通常の編集。元のローマ字からの削除は Swift の正規打鍵列を使う。
- 変更箇所を強調、長文の共通部分は省略。

### 6.2 生成経路 (`append_ai_assist_candidates`)

1. 通常変換の AppendText/ReplaceComposition はモデル補正を待たず応答する。日付・計算などの決定的マクロは即時追加。
2. クライアントは入力・候補・COM context/composition・接続/文脈・モデル設定・要求世代のスナップショットを作成。60ms 入力が止まると `GetAssistCandidates` を別タスクで呼ぶ。
3. サーバーは Swift や composition の変更ロックを使わず、専用ワーカーで補正と先読みを生成。補正と先読みは独立した RPC・生成枠。各枠の推論は 1 件。HTTP 読取量・時間、候補数、キャッシュ件数を制限。
4. 25ms の TSF スレッドタイマーで結果を受け取る。削除・再入力・候補操作・文節移動・確定・フォーカス変更等で世代や状態が変わった結果を破棄。同じ文字列を打ち直した場合も古い結果を使わない。
5. 補正は元の候補を保持して追加する。適用時に補正表記を direct 入力として converter へ絶対置換し、原文の composition と入力 ledger を復元用に保存。補正適用とアプリへの確定は別操作。

### 6.3 補正の内部パイプライン (`ai_assist/corrector`, `keyboard_hybrid`)

`ai_assist::corrector::suggest_composition(context, target, first_surface, config)` が補正候補を生成する。v0.2.3以降、独立Correctorサーバー（18090番）は廃止され、ネイティブワーカー（`keyboard-prior.exe`）の探索とメイン変換モデル（ポート18091）による2段リランキング（Stage-1/Stage-2）・漢字変換へ刷新された。フォールバック順序:

1. 浮いたローマ字子音の復元 (`romaji_decoder.rs`)
2. 採用済み学習 (`learning.rs`)
3. 固定タイポ表
4. 語中・音韻規則
5. 編集距離
6. QWERTY 辞書検索 (`typo_variant.rs` / `builtin_vocab.tsv`)
7. ネイティブ探索・メイン変換モデル統合補正（`keyboard_hybrid.rs` / `keyboard-prior.exe` `--assist`、ポート18091）

入力は 128 文字までは全文を渡す。長い composition は通常変換の句読点境界を照合できる場合のみ末尾の句を補正し、変換済み接頭部を保持。境界を確認できない長文は任意の文字位置で切らない。要求全体の上限は 512 文字。

出力予算は入力長に応じ 32〜384 トークン。打ち切り・制御文字・空出力は採用しない。正常な既知の読みを保護し、学習済みの完全一致を優先。混在するローマ字は一般的な近接キー復元で候補を作る。その他では corrector の正常終了結果を曖昧なルールより優先し、変更なしの結果をルールで覆さない。モデル不通時は既存の辞書・規則で提案する。

### 6.4 逆引き辞書による保護

既存の `Dictionary/Reverse/reverse-v2.bin`（AZR2）を背景ワーカーで読み、補正表記が元の読みのまま読める場合は通常変換として扱い、補正の提案を省く。Swift の状態や変更ロックには触れない。

### 6.5 自動反映の条件

自動反映は以下に限定:

- 完全な辞書語へ一意に復元できる隣接キー誤入力
- 同じ補正を明示採用して確定した記録が 3 回以上

さらに以下は自動反映しない:

- 末尾が入力途中の子音
- 全体が英字だけの識別子
- 曖昧な候補

自動反映だけで採用回数を増やさず、取消・未確定・確定失敗を学習に数えない。モデル確率を正解率として扱わない。

### 6.6 関連ファイル

- `windows/crates/server/src/ai_assist/mod.rs`
- `windows/crates/server/src/ai_assist/corrector.rs` (推定、mod 配下)
- `windows/crates/server/src/ai_assist/romaji_decoder.rs`
- `windows/crates/server/src/ai_assist/typo_variant.rs`
- `windows/crates/server/src/ai_assist/learning.rs`
- `windows/crates/server/src/ai_assist/collocation.rs`
- `windows/crates/server/src/ai_assist/dynamic_macro.rs`

---

## 7. 先読み (予測変換)

補正候補が出ていれば生成を省略する（`kind=2` の先読み要求では補正を生成しないが、補正が既に表示中なら先読みは補正を待たない）。共起語を優先し、それがなければ定型句→確定履歴→同一プロンプトキャッシュ→モデル推論へ進む。モデル推論には 60ms の連続呼び出し抑制と同時リクエスト抑制がある。

### 7.1 生成と表示

- 通常変換の応答後、60ms 入力が止まると補正 (`kind=1`) と先読み (`kind=2`) を別 RPC で要求。生成枠も別で、先読みは補正モデルの終了を待たない。
- 補正は手動提案なら先読みの応答まで保留。先読みが先に届けば直ちに表示できる。自動反映できる狭い根拠の補正は保留しない。
- 入力・候補・COM context/composition・文脈・設定・要求世代が一致する結果だけ反映。片方を追加した際だけ、その追加後の候補を次の結果の照合対象にする。
- 自動/Tab 補正は未確定のまま反映し、直後の Esc で元の打鍵履歴へ戻す。補正後は文脈の epoch を同期して先読みだけを再要求する。
- 表示中の Tab 対象は次の編集まで固定。遅い手動補正が届いても表示済み先読みを置き換えない。補正は通常候補一覧の「補正」からも選べる。
- 先読みは追加部分と「Tab で確定」を表示。補正の提案は「Tab で補正」、適用済み補正は「Esc で戻す」。先読みと補正の取消表示は共存できる。
- 先読みの Tab は現在の表記 + 追加部分の全文を確定する。弱い補正を未採用のまま先読みへ混ぜない。

### 7.2 妥当性の初期評価 (relevance)

RPC の `relevance` は根拠に基づく順序値であり、モデル確率や正解率ではない。

| 候補 | 値 |
|---|---:|
| 一意な近接キー復元等、自動反映の根拠がある補正 | 100 |
| 学習済みの手動補正 | 70 |
| 表記を維持し、出力検査を通過したモデル先読み | 60 |
| 独立した根拠のないモデル・規則補正 | 30 |

最初の表示時点で揃っている候補の値を比較。同値では先読みを選ぶ。出力検査は文脈への完全な意味適合を保証しない。

### 7.3 先読みの入力とモデル (ZeroCon)

読み・通常変換済み表記・追加部分を別々に扱う。先読み候補は ZeroCon Model だけで生成する。定型句・共起表・採用履歴による候補生成と LFM 先読みは使用しない。モデルが不通、または出力検査に不合格なら先読みを表示しない。

ZeroCon は `EE02 + 左文脈 + EE00 + カタカナ読み` から読みの続きを生成し、短い句の境界までを取り出す。その後 `EE02 + 左文脈 + EE00 + 延長した読み + EE01` で表記へ変換する。変換結果が現在の表記で始まらなければ破棄する。生成かなをそのまま本文に追加しない。

- 読み生成 24 トークン・250ms、表記変換は入力長に応じ最大 256 トークン・300ms。左文脈は末尾 256 文字。
- 生成上限で切れた断片、制御文字、記号だけ、表記の繰り返し、原表記の変更を除外する。読みの生成上限に達しても、その前に完結した短い句があれば利用する。
- 学習文の「この記事」「上記」等を文脈の根拠なしに追加する出力を除く。
- 補正後の direct 入力表記に漢字がある場合は、背景ワーカーで逆引き辞書から読みを復元。復元できなければ先読みは省く。
- モデルキャッシュはポート/左文脈/読み/表記をキーに 32 件。設定再読込時に消去する。

### 7.4 起動と設定

| 用途 | 接続先・モデル |
|---|---|
| Rust 補正・ZeroCon 先読み（統合） | 18091、実行フォルダの `zenz.gguf`（メイン変換モデル）、漢字変換と共通の `zenzai.backend`（既定 Vulkan、CPU/CUDA も選択可） |
| 設定画面のベンチマーク | 8090。実 IME の先読み・補正とは別プロセス |
| （旧構成）Corrector 専用 | 18090。v0.2.3で完全撤廃され、18091のメイン変換モデルへ一本化 |

launcher は AI アシスト有効時に常駐推論サーバー（ポート 18091）を監視する。従来の独立した Corrector サーバー（18090 番）は廃止され、メイン変換モデル 1 本で先読み・タイポ補正（2段リランキング）・漢字変換を担う。先読みモデル選択は廃止し、旧 `experimental_predictive_model` は読み込み時に無視・次回保存時に除去する。タイポ補正の `experimental_ai_engine` は先読みには影響しない。所有するプロセスだけ設定変更・異常終了に応じて停止/再起動する。Job Object により launcher 終了時もサーバープロセスを終了する。外部の待受プロセスは停止しない。

モデル切替時はセッション内の一時イベントで先読み停止を要求し、launcher が所有プロセスの停止を確認してから重みを差し替える。変換側の watchdog も切替中の再起動を待つ。

先読みの採用履歴と採用通知は廃止した。旧クライアントの通知は互換性のため認証後に無処理で応答し、composition の所有権を取得しない。タイポ補正の学習データは維持する。

### 7.5 関連ファイル

- `windows/crates/server/src/main.rs` の `append_ai_assist_candidates`（先読み呼び出し）
- `windows/crates/server/src/ai_assist/mod.rs` の `fetch_predictive_candidate_internal` / `prediction::suggest`
- `windows/crates/server/src/ai_assist/collocation.rs`

---

## 8. 候補操作・文節・確定・学習

### 8.1 文節移動・調整

- `MoveCursor` / `ShrinkText` / `AdvanceClause` / `PrepareFutureClauses` / `AdjustClauseBoundary` で文節単位のナビゲーション。
- `UpdateCompositionSnapshot` で clause スナップショットを push/pop/clear。サーバーは選択された candidate_id だけ保持（eviction 時）。
- clause 境界は raw input 要素単位で表現し、別ローマ字綴りが原子的に保たれる。

### 8.2 再変換 (Reconversion)

マウスなどで確定済み文章を選択して Space を押すと、選択範囲を再変換する。設定の再変換キーも引き続き使用でき、「無効」では Space 再変換も停止する。選択範囲がなければ通常の空白入力を行う。再変換に失敗した場合や対応範囲を超える場合は選択文字列を保つ。Esc で再変換前の表記へ戻せる。

`StartReconversion` で確定済み表記から読みを推定し、composition を再構築する。サーバー側で `infer_reconversion_readings` / `decode_reconversion_readings`。最大 128 surface、4 readings、100 suggestions。成功時のみ composition を置換（失敗時は現状維持）。

選択中は TSF の `ITfTextEditSink::OnEndEdit` で選択範囲を読み、選択端の近くに文字数を表示する。表示はフォーカスを奪わないネイティブウィンドウで、選択解除・入力開始・フォーカス移動時に消える。Unicode のコードポイント単位（改行 CRLF は1文字）で数える。コールバック内の読み取りは UTF-16 65,536 単位までとし、超過時は読み取れた数を「文字以上」と表示する。本文は文字数表示のために外部へ送信しない。

### 8.3 確定と学習

- Enter は選択中の文節までを確定する。矢印キーで文節を選び、Space や F6〜F10 で変換した場合も、後続の文章を未確定のまま残して続けて変換できる。後続がない場合は composition を終了する。
- `CommitLearningCandidate` / `CommitLearningCandidates` で確定通知。candidate_id と commit_kind（NORMAL/PUNCTUATION/PARTIAL）を送る。AI アシスト候補（予約 ID）は surface を渡して解決。
- `swift_commit_learning_candidate` / `swift_commit_learning_candidates` が Swift の学習に渡す。AI アシスト候補は `handle_ai_assist_learning_commit` で専用記録（`ai-assist-learning.json`、最大 500 件）。
- `ResetLearningMemory` で学習記憶をリセット。
- `CommitLearningCandidateRequest` の `typo_reading` で補正採用を明示的に記録（グローバルな「最後の候補」競合を回避）。

### 8.4 候補のサーバー側再ランキング

`RerankCandidates` はクライアントスナップショットに対する Stateless 選択。composition を変更しない。`allow_remote` でリモート利用可否。

---

## 9. 設定・モデル・ポート

### 9.1 設定の流れ

設定は `shared::AppConfig`。Tauri コマンド → `shared::AppConfig` 保存 → IPC でサーバー適用（`UpdateConfig` / `reload_ai_assist_from_settings`）→ Swift の `LoadConfig`。TS 定義 (`frontend/src/lib/config.ts`) と Rust 定義の整合が必要。

実設定は `%APPDATA%\Azookey\settings.json`。配布フォルダの `settings.json` は初期コピー元。

### 9.2 主な設定項目

| 設定 | 意味 |
|---|---|
| `general.experimental_ai_assist` | Rust 側補正・先読みの有効化 |
| `general.experimental_typo_correction` | Swift 側キーボードタイポ補正 |
| `general.jev_conversion` | JEV 変換モード（自動確定無効化など） |
| `zenzai.backend` | Vulkan/CPU/CUDA |
| `predictive_model` | 旧設定、読み込み時に無視・次回保存時に除去 |
| `experimental_ai_engine` | corrector 用エンジン (embedded/LM Studio) |
| `experimental_predictive_model` | 廃止 |

### 9.3 モデル・ポート

- v0.2.3 以降、常駐する推論サーバーはメイン変換モデル（ポート 18091、Nano v4 等）の単一プロセスへ統合された。旧 18090 番の独立 Corrector サーバーや LFM フォールバックは完全撤廃された。
- 先読み・タイポ補正（Stage-2 表層リランキングおよび再変換）ともにポート 18091 のメイン変換モデルを利用する。
- 設定画面のベンチマークは専用の 8090 番で起動し、実 IME のプロセスとは分離されている。
- 配布設定の確認値: Zenzai 有効、Vulkan、タイポ補正・AI アシスト・自動確定有効、engine `embedded`。ただし実行中の AppData 設定を保証しない。

### 9.4 保存ファイル

| ファイル | 内容 |
|---|---|
| `%APPDATA%\Azookey\settings.json` | 実設定 |
| `%APPDATA%\Azookey\ai-assist-learning.json` | 補正学習（最大 500 件） |
| `%APPDATA%\Azookey\ai-assist-vocabulary.json` | 追加補正語彙 |
| Rust プロセス内 | 先読みの確定履歴キャッシュ（最大 300 件） |

---

## 10. 表示 (UI)

- `crates/ui/src/ruby.rs` が読み表示（ルビ）を描画。旧形式・構造化形式の表示、Tab/Esc、画面幅、拡大率、明暗テーマを検証。
- 候補一覧は `crates/ui/`。候補スクロール JS テストは `node --test crates/ui/tests/candidate_scroller.test.cjs`。
- 候補の subtext に「補正」「💡 もしかして」等を表示。

---

## 11. 設計上の注意・制約

- 入力順序、読み、未確定ローマ字、文字数、候補 ID、文脈を別々に扱う。
- タイポ補正と先読みの優先順、通常候補の非破壊性を保つ。
- `ai_assist/mod.rs` 冒頭コメントは推論エンジン非依存だが、実際には同期 TCP/HTTP フォールバックがある。タイポ補正 150ms 等のタイムアウトを含めて遅延を調べる。
- 先読み関数の引数名は `partial_surface` だが、server からは `hiragana_text` が渡る。定型句表には漢字表記もあるため、読み・変換済み表記・継続文字列の境界を追う。
- 実行バイナリとソースが一致するとは限らない。モデル名・設定・ポート・実行パスを確認してから不具合を判断する。
- 配布 DLL/EXE、モデル重み、ビルド出力、依存取得物を直接修正しない。

---

## 12. 検証

`windows/` で実行する。

```powershell
cargo test -p azookey-windows --lib --offline
cargo test -p azookey-server --lib --offline
# Swift DLLと依存DLLがPATHにある環境
cargo test -p azookey-server --bin azookey-server --offline
node --test crates/ui/tests/ruby_reading.browser.mjs
```

ブラウザテストは Playwright が必要。`PLAYWRIGHT_MODULE` にモジュールの URL、必要に応じ `PLAYWRIGHT_CHANNEL=chrome` を指定する。

実サーバー接続テスト:

```powershell
cargo test -p azookey-windows --lib --offline test_live_server_okseri -- --ignored --nocapture
```

先読みのモデル評価:

```powershell
# ZEROCON_PREDICTION_TEST_PORT を指定
cargo test -p azookey-server --lib --offline live_prediction_evaluation -- --ignored --nocapture
```

新しい DLL をロードした入力先アプリで、表示、入力継続、Tab、Esc、Space、Enter、取消、フォーカス移動も確認する。既存アプリは更新前の DLL を保持するため、IME プロセスの再起動後に入力先アプリも開き直す必要がある。

---

## 13. 探索の起点

| 症状 | 最初に読む |
|---|---|
| タイポ候補が生成されない・誤補正する | `crates/server/src/ai_assist/mod.rs` の `fetch_typo_suggestion` → `romaji_decoder.rs` / `typo_variant.rs` / `learning.rs` |
| 補正があるのに表示・Tab 採用できない | `crates/server/src/main.rs` の `append_ai_assist_candidates` → `crates/client/src/engine/composition.rs` の `ai_assist_priority_index` と候補フィルタ・キー処理 → `crates/ui/src/ruby.rs` |
| Swift 変換側のタイポ補正 | `server-swift/Sources/azookey-server/azookey_server.swift` の `getOptions` / `confidentKeyboardTypoCorrectionForZenzaiMerge` → `data/keyboard-typo-dictionary.tsv` |
| 先読みが不自然・出ない | `crates/server/src/main.rs` の先読み呼び出し → `crates/server/src/ai_assist/mod.rs` の `fetch_predictive_candidate_internal` → `collocation.rs` → launcher の起動モデル |
| 入力遅延・モデル接続・設定と違うモデル | `crates/launcher/src/main.rs` の `start_ai_server_process` → `crates/server/src/ai_assist/mod.rs` の `simple_http_post` → 配布先のモデル・backend |
| 自動確定・削除・カーソル・文節の不整合 | `crates/client/src/engine/composition.rs` → `composition/tests/` → `crates/server/src/composition_owner.rs` |
| 設定変更が反映されない | `crates/shared/src/lib.rs` → `frontend/src/lib/config.ts` / `pages/general.tsx` → `frontend/src-tauri/src/lib.rs` → server の `reload_ai_assist_from_settings` / Swift の `LoadConfig` |
