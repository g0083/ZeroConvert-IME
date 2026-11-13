# 学習削除の共通化（スコープ別リセット＋候補単位削除）

## 目的

Androidの候補バーから候補1件の学習を削除できるようにし、全削除も削除対象を選べるようにする。判断と辞書操作は共有エンジン（Swift/Rust）に置き、ホストは候補IDと確認UIだけを持つ。

## 変更前の状態

| 項目 | Windows | Android |
| --- | --- | --- |
| 全削除 | 設定画面から `ResetLearningMemory` を1回呼ぶ | 同RPCを呼ぶ。確認ダイアログ・結果通知なし |
| 単語削除 | 手段なし | 手段なし |
| 補正学習 | リセット対象外 | リセット対象外（Kotlin `ai_assist_learning` と Rust `ai-assist-learning.json` の二重保存） |
| 削除結果 | `bool` のみ | `bool` のみ |

`LearningMemory.forgetMemory` は上流azooKeyに存在するが、呼び出し元が無く、`learningType.needUpdateMemory` で門番されていた。読み取り専用モードでは何も消えない。

## エンジン契約

`windows/crates/shared/service.proto` に3つ追加した。

```proto
rpc ForgetLearningCandidate (ForgetLearningCandidateRequest) returns (ForgetLearningCandidateResponse);
rpc GetLearningStats (LearningStatsRequest) returns (LearningStatsResponse);
rpc ResetLearningMemory (ResetLearningMemoryRequest) returns (ResetLearningMemoryResponse);

enum LearningMemoryScope {
  LEARNING_MEMORY_SCOPE_UNSPECIFIED = 0;
  LEARNING_MEMORY_SCOPE_CONVERSION = 1;
  LEARNING_MEMORY_SCOPE_TYPO = 2;
}
```

- `ForgetLearningCandidateRequest` は `candidate_id` と、補正候補用の `typo_reading` / `surface` を持つ。
- `LearningStatsResponse` は `selection_count` / `history_present` / `history_paused` / `learning_mode` / `typo_count` を返す。
- `ResetLearningMemoryRequest.scopes` が空なら全スコープ（旧クライアント互換）。レスポンスは `applied` / `failed` / `stats` を返す。

Androidは同じSwiftエンジンを直接呼ぶため、`engine/swift/AndroidBridge.swift` に `op=forget` / `op=learningStats` を追加した。`op=resetLearning` は `stats` も返す。

## 単語削除の経路

```
候補バー長押し / 候補ウィンドウ右クリック
  → WindowsEngine.forgetCandidate(candidateId)     (Android)
  → ENGINE_CANDIDATE_FORGET_MESSAGE → クライアントで index → candidate_id
  → ForgetLearningCandidate RPC                    (Windows)
  → forget_learning_candidate(candidateId)         (Swift)
       ├─ learningCandidate(candidateId)              … 非破壊の検証用参照
       ├─ 学習対象・学習モードの検査                    … 拒否時はIDを消費しない
       ├─ LearningCandidateCache.consume(candidateId) … 検査通過後に単発削除のため消費
       ├─ converter.forgetMemory(candidate)        … LOUDS長期・一時記憶
       └─ LearningSelectionStore.remove(output:for:) … 選択上書きは表記単位
```

`LearningSelectionStore.remove(output:for:)` を新設した。既存の `removeValue(forKey:)` は読みごと全消しで、同じ読みの別表記まで巻き添えになるため使わない。

候補IDは `consume` で消費する。非破壊の peek だと同じIDを2回削除でき、後から別の候補が同じスロットを再利用したときに誤削除につながるため。実機テストで2回目が拒否されることを固定した。

**検証は consume の前に行う。** 学習対象外の候補（辞書補正・AI候補）や `currentLearningType == .nothing` の候補は削除を拒否するが、先に consume するとそのIDが恒久的に使用済みになり、以後の学習確定（`CommitLearningCandidate`）も `PinLearningCandidate` も失敗する。`LearningCandidateCache.candidate(for:)` で内容を読み、ガードを通ってから consume する。

### 実機テスト用の非破壊検証

実機テストは `LearningPolicy.withoutWrites` の内側で動くため、実ストアを書き換えてはいけない。`ValidateLearningCandidateDeletion`（`validate_learning_candidate_deletion`）は同じガードと consume を適用し、ストア書き込みの手前で戻る。これによりIDの発行・単発消費の契約を実データに触れずに検証できる。`AndroidBridge.swift` の `op=validateForget` がこの経路。

### Windows候補ウィンドウ

候補ウィンドウは `Vec<String>` しか持たないため、右クリックは表示indexだけを `ENGINE_CANDIDATE_FORGET_MESSAGE` でTSFへ送り、クライアントが `Composition.candidates.candidate_ids` でIDを解決する。IDが0（辞書補正・AI候補）なら何もしない。削除成功後は `MoveCursor(0)` でサーバーに同じ読みの変換をやり直させる。

`MoveCursor(0)` を選ぶのは、`Previewing` 状態の Space / Tab / Reconvert が `candidate_preview_actions`（`SetSelection(Down)`）へ解決され、候補一覧を再変換しないため。キー経路の計画に頼らず、再変換要求を直接組み立てる。あわせて `SetSelection(Number(0))` で再ランキング後の先頭を選択し直す。

`ChangeWindowMessageFilterEx` で新メッセージも許可する。UIAccess を失った候補UIから昇格ウィンドウへ届かせるため。

## 依存入力の変更（Phase 0）

`windows/scripts/converter_patch.py` に2つの置換を追加した。

1. `forgetMemory` の門番を `needUpdateMemory` から `needUsingMemory` へ。削除は明示操作であり、新しい学習の許可とは別。
2. `LearningManager.resetMemory` の後に `memoryCollapsed` を再計算。`.pause` が残ったまま学習が無効化される問題を防ぐ。

`prepare-modern-llama.py --check-converter` がアンカーを検証する。テストは `windows/scripts/test_converter_patch.py` の `LearningMemoryTests`。

`resetLearningMemoryDirectory` は学習無効時にディレクトリを作り直さないよう変更した。空ストアを作ると次回起動時に履歴があるように見えるため。

## 補正学習の削除

`windows/crates/server/src/ai_assist/learning.rs` に追加:

- `TypoLearningStore::remove(reading, surface)` — 読みと表記の両方を照合
- `TypoLearningStore::remove_all()`
- `forget_learned_typo` / `clear_learned_typos` / `learned_typo_count`

共有Rustブリッジ（`engine/rust/src/bridge.rs`）に `forget` / `forgetAll` / `learningStats` のopを追加した。Androidの `TypoLearning.forget` / `clear` は Kotlin の Prefs と Rust の両方を消す。片方だけだと再起動後に復活する。

## UI

**Android**
- `HorizontalCandidateStrip` に `onForget` を追加。候補長押しで確認ダイアログを出す。
- **確認 UI はダイアログではなく候補バー内のインライン表示にする。** IME は Activity のウィンドウトークンを持たないため、`AlertDialog` は `WindowManager$BadTokenException` でクラッシュする。`Dialog` / `Popup` 系を IME ウィンドウ内で使わない。
- 候補の長押しは `combinedClickable` だけではアクセシビリティに公開されないため、`semantics { onLongClick(...) }` も併記する。
- 候補リストの描画は `items(list) { }` を使う。`items(size) { candidates[index] }` は削除でリストが縮んだ瞬間に範囲外参照になる。
- 削除後は同じ読みで再変換する。設定画面は「学習データ」に変換学習・補正学習の件数を表示し、削除スコープを選ばせる。

**Windows**
- 設定画面の「変換学習」に件数表示とスコープのチェックボックスを追加。`reset_learning_history` の `applied` / `failed` / `stats` を反映し、部分失敗を成功として表示しない。
- `get_learning_stats` / `forget_learning_candidate` コマンドを追加。
- 候補ウィンドウは右クリック（`contextmenu`）で削除要求を送る。左クリックの採用経路は維持する。

## 制約

- 上流の忘却は `forgetTargetWords` で表記のみ照合する。`かんじ→漢字` を消すと、別の読みで学習した `漢字` も消える。Gboardも表記単位なので仕様として許容する。
- 複数形態素の候補を消すと、含まれる語もまとめて消える。文言は「この候補の学習を削除」とし、単語単位と説明しない。
- 候補IDは新しい変換でキャッシュから追い出される。無効なIDは `false` を返し、UIは「削除できませんでした」と伝える。
- 削除APIは `isLearningTarget == false` の候補（AI生成・辞書補正）を拒否する。

## 検証

| 層 | コマンド | 結果 |
| --- | --- | --- |
| 依存パッチ | `python windows/scripts/prepare-modern-llama.py --check-converter` | anchors ok |
| 依存パッチ単体 | `python windows/scripts/test_converter_patch.py` | 追加4件PASS（既存5件は別セッションの `n_batch` アンカー未追随で失敗） |
| Rust補正学習 | `cargo test -p azookey-server ai_assist::learning` | 7 passed |
| 共有Rust | `cargo test -p zerocon-engine` | 79 passed / 1 ignored |
| Windows全体 | `cargo test --workspace` | 全suite成功（16 suites） |
| Windows UI | `cargo test -p ui` | 52 passed |
| Windowsビルド | `cargo build --workspace` | 成功（Swift静的ライブラリ再ビルド後） |
| フロント型 | `npm run typecheck` | エラーなし |
| フロントテスト | `npm test` | 18 passed |
| Android JVM | `:rebuilt:testDebugUnitTest` | 36クラス 失敗0 |
| Android lint | `:rebuilt:lintDebug` | エラー0 |
| Android UI (エミュレータ) | `am instrument -e keyboardUiOnly true` | **22 passed / 0 failed** |
| Android APK | `scripts/check-android-apk.py` | 両ABIでclosure・CPU backend・逆引き辞書・モデルハッシュ検証OK |
| 実機（V2520A / Android） | `am instrument` 27 tests | **27 passed / 0 failed** |

### 追加修正（2026-11-12）

レビューで見つかった2点を修正した。

1. **consume の順序**: 検証を consume の前に移した。拒否された削除が有効なIDを消費して、以後の学習確定・pin を壊す経路を塞いだ。回帰は Swift テスト `refusedDeletionLeavesTheCandidateUsable` と `refusedDeletionWhileLearningIsDisabledKeepsTheCandidate`。
2. **実機テストの非破壊化**: `validateCandidateDeletion`（`ValidateLearningCandidateDeletion`）を追加し、ガードと consume だけを適用してストアには書かない。`PortInstrumentationRunner` の学習系2テストはこの経路を使う。学習モードが読み書き以外の端末ではIDが発行されないため、その場合はスキップ理由をログに残して受け入れ側の確認だけ省略する。回帰は Swift テスト `deletionValidationConsumesWithoutWritingStores`。

| 追加検証 | 結果 |
|---|---|
| `swift test --filter refusedDeletion` | 2 passed |
| `swift test --filter deletionValidation` | 1 passed |
| `swift test --filter learningCandidate` | 7 passed |
| `:rebuilt:testDebugUnitTest` | 284 tests, 0 failures |
| エミュレータ `am instrument`（学習系2件） | PASS（他は環境要因で失敗） |

`swift test` 全体では `latestLearningSelectionIsPrioritizedOnlyForItsReading` が失敗するが、これは変更前の HEAD でも同じく失敗する既存問題（`prioritizeLearningSelectionOverrides` の並び順期待）で、今回の変更とは無関係。

### 実機で確認したこと

1. **APK照合**: 端末の `base.apk` の SHA256 がビルドと一致（`34DAEBCD...` → 再ビルド後 `67ecc8a8...`）。
2. **設定画面の件数**: 「変換学習 67件 / 補正学習 1件」が表示された。
3. **スコープ指定**: 補正学習のチェックを外して削除 → 「変換学習 0件 / 補正学習 1件」となり、変換学習だけが消えた。
4. **往復削除**: 合成読み `ぜろこんけしょう` の候補IDを削除 → 成功。同じIDの2回目は拒否。
5. **無効ID**: `0` と `UInt64.max` は削除拒否。
6. **回帰**: 既存25件（変換・カーソル・英語・辞書・Zenzai長文）は全てPASS。

### 実機で確認したこと（2026-11-12〜13）

- 候補バーの長押しから開く確認 UI: 実機の候補バー上で長押し → インライン確認 → キャンセル／削除の一連を目視確認した。修正前の `AlertDialog` による `BadTokenException` は再現しない。
- Windows実IMEでの右クリック削除: 試験IMEを常駐させた状態で右クリック → 確認 → 削除 → 候補一覧の再変換まで動作を確認した。

### エミュレータで判明した環境制約

- `zerocon-verify` (x86_64 / API 36) では `adb shell input tap` が IME ウィンドウに届かない。
- instrumentation でも、実 IME のウィンドウを使うテスト（`real_ime_keys_and_commit`、`english_*`）は `Japanese IME key did not appear` で失敗する。`ImeTracker` が `PHASE_WM_GET_CONTROL_WITH_LEASH` でタイムアウトしており、エミュレータのウィンドウ制御の問題。アニメーション無効化・GPU 切り替え・エミュレータ再起動では解消しなかった。
- そのため長押し削除の検証は、IME ウィンドウを使わない `KeyboardUiRegression` のフィクスチャに移した。ここでは 22 件すべて PASS し、クラッシュも発生しない。

## 未対応

- 英語学習（`zerocon_english_data`）と絵文字履歴は削除スコープに含めていない。
- 上流 `forgetMemory` の表記単位照合により、同じ表記を別読みで学習していた場合も消える。仕様として許容。

## 修正（2026-11-12・Windows右クリック削除の再変換）

実機で右クリック削除を試すと、候補の選択が2番目へ移るだけで一覧が変わらなかった。`reconvert_after_learning_deletion` が `UserAction::Space` を計画していたのが原因で、`Previewing` ではこれが `SetSelection(Down)` になり再変換が走らない。`MoveCursor(0)` でサーバーの `GetComposedTextForCursorPrefix` を呼び直す実装へ変えた。

| 検証 | 結果 |
| --- | --- |
| `cargo test -p azookey-windows` | 403 passed / 0 failed（追加2件を含む） |
| `cargo fmt -p azookey-windows -- --check` | 差分なし |
| `cargo clippy -p azookey-windows --all-targets` | 新規警告なし |
| 実IME（メモ帳、`Build-Trial.bat` 適用後） | 右クリック1回目で「もう一度右クリックで削除」表示、2回目で候補が 日本語／にほんご／2本後／2本語／日本後 に入れ替わり、選択が先頭へ戻ることを確認 |

再変換後の候補から `日本語で` が消えることを目視確認した。学習ストアへの書き込み時刻も更新されている（`selection-overrides.json`）。
