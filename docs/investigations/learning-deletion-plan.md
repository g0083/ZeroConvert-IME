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
候補バー長押し
  → WindowsEngine.forgetCandidate(candidateId)     (Android)
  → op=forget / ForgetLearningCandidate RPC        (Windows)
  → forget_learning_candidate(candidateId)         (Swift)
       ├─ LearningCandidateCache.candidate(for:)   … 消費せずに候補を引く
       ├─ converter.forgetMemory(candidate)        … LOUDS長期・一時記憶
       └─ LearningSelectionStore.remove(output:for:) … 選択上書きは表記単位
```

`LearningSelectionStore.remove(output:for:)` を新設した。既存の `removeValue(forKey:)` は読みごと全消しで、同じ読みの別表記まで巻き添えになるため使わない。

`LearningCandidateCache.candidate(for:)` は非破壊の peek。既存の `consume` は学習確定用に温存する。

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
- `HorizontalCandidateStrip` に `onForget` を追加。候補長押しで確認ダイアログを出し、削除後は同じ読みで再変換する。
- 設定画面は「学習データ」に変更し、変換学習・補正学習の件数を表示。「学習を削除」でスコープ選択ダイアログを開く。
- 削除結果はToastで通知し、件数を取り直す。

**Windows**
- `reset_learning_history` が `scopes: Option<Vec<String>>` を受け取る。未知の名前は無視し、全消去へ拡張しない。
- `get_learning_stats` / `forget_learning_candidate` コマンドを追加。

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
| Windows全体 | `cargo test --workspace` | 全suite成功 |
| Android JVM | `:rebuilt:testDebugUnitTest` | 36クラス 失敗0 |
| Android lint | `:rebuilt:lintDebug` | エラー0 |
| APK | `scripts/check-android-apk.py` | 両ABIでclosure・CPU backend・モデルハッシュ検証OK |

未実施: 実機での長押し削除・リセット動作確認。候補IDの寿命と実IMEの再変換挙動は端末で確認する必要がある。

## 未対応

- Windows候補ウィンドウの右クリック削除UI。RPCとコマンドは用意済みで、UIの配線が残っている。
- 英語学習（`zerocon_english_data`）と絵文字履歴は削除スコープに含めていない。
- 削除後の再ランキングを検証する自動テスト。現状は手動確認のみ。
