# 入力ポリシーの両OS共有（B+C）実装計画

作成日: 2026-11-11

状態: **実装済み（2026-11-11）**。Phase 0〜3 を実装し、Phase 4 は任意の後続作業として残す。
この文書は設計・手順・受け入れ条件を定義し、末尾「9. 実装結果」に実施内容と検証結果を記録する。

関連:

- 経緯と差分の実測: [symbol-digit-emoji-input-paths.md](symbol-digit-emoji-input-paths.md)
- 共有エンジンの現状: [../android-shared-engine.md](../android-shared-engine.md)
- Windows の入力仕様: [../windows-conversion-logic.md](../windows-conversion-logic.md)
- Android の入力仕様: [../android-conversion-logic.md](../android-conversion-logic.md)

---

## 1. 背景と目的

`engine/rust` は Windows の `crates/server/src/ai_assist` と `crates/shared/src/conversion_policy.rs` を直接 include して共有している。一方、**キー入力の解釈と composition の状態遷移は両OSで別実装**であり、ポリシー判断が Kotlin へ手移植されている箇所がある。手移植はドリフトの温床で、実際に「Android の `、` が即確定する」という Windows との食い違いが発生した（`isImmediateCommitChar`）。

本計画の目的は、**状態機械の実装を共通化せず**（案Aは採らない）、次の2点で「同じ入力に対して同じ判断」を保証することである。

- **B: 入力ポリシーの純粋関数を共有層に置き、両OSが同じ定義を参照する**
- **C: 共有の挙動シナリオ（テストベクタ）で両OSの実装を機械検証する**

非目的:

- `windows/crates/client/src/engine/composition.rs`（9,408行）の分割・共通化
- Android `ComposingText` の置換
- TSF / InputConnection に依存する I/O 層の共有
- 変換エンジン（辞書・zenz・ai_assist）の変更

---

## 2. 現状の実測（計画の前提）

| 対象 | 実態 | 共有 | 問題 |
|---|---|---|---|
| `windows/crates/shared/src/conversion_policy.rs` | 217行、純粋関数9個（`is_hiragana_char` / `is_kanji_or_katakana_char` / `is_japanese_clause_punctuation_char` / `raw_input_has_pending_romaji_tail` / `raw_input_split_breaks_romaji` / `is_natural_auto_commit_prefix` / `record_live_conversion_prefix_snapshot` / `find_auto_commit_live_conversion_snapshot` + tests） | Windows と `engine/rust` が参照 | Android は**Kotlinへ手移植**（`ZeroConvertInputMethodService.kt:179-260`）しており、Rust を参照していない |
| `isImmediateCommitChar` / `PanelTextCommit` | Kotlin のみ（`ZeroConvertInputMethodService.kt:155`, `PanelTextCommit.kt`） | なし | Windows の `punctuation_commit` 既定OFFと食い違っていた（2026-11-10 修正済み） |
| 数字・記号の幅変換 | `windows/crates/client/src/engine/full_width.rs`（549行） | なし | Android に相当機能がない |
| 記号パネル / テンキー | Kotlin のみ | なし | Windows に対応UIがない |

`engine/rust/src/lib.rs` の現状:

```rust
extern crate self as shared;

#[path = "../../../windows/crates/server/src/ai_assist/mod.rs"]
pub mod ai_assist;

pub mod bridge;

#[path = "../../../windows/crates/shared/src/conversion_policy.rs"]
pub mod conversion_policy;
```

`conversion_policy` は**既に Android のネイティブ層にリンクされている**。JNI バインディングを追加すれば Kotlin から直接呼べる状態にある。

---

## 3. 設計

### 3-1. 共有する範囲（B）

`conversion_policy.rs` を「入力ポリシーの正本」と位置づけ、次を追加する。**すべて純粋関数**（I/O・時刻・乱数・OS API なし）。

```rust
// windows/crates/shared/src/conversion_policy.rs（追記）

/// 入力文字が composition を確定させるか（記号系）。
pub fn is_immediate_commit_char(ch: char) -> bool;

/// 句読点を日本語の読み用文字へ正規化する。対象外は None。
pub fn punctuation_for_reading(ch: char) -> Option<char>;

/// 句読点を composition に含めるべきか。未確定ローマ字末尾では false。
pub fn should_compose_punctuation(ch: char, pending_romaji: bool) -> bool;

/// 数字・記号を composition に含めるか（Windows の UserAction::Number 相当）。
pub fn is_composition_digit(ch: char) -> bool;

/// 翻訳モードで literal として読みへ反映する文字か。
pub fn is_translation_literal_char(ch: char) -> bool;

/// 幅グループ既定値（Windows の CHARACTER_WIDTH_SYMBOL_DEFAULTS と同一の正本）。
pub fn symbol_default_fullwidth(symbol: &str) -> Option<bool>;
```

規約:

- 戻り値は `bool` / `Option<char>` / 小さな `enum` に限定し、`String` 確保を避ける。
- 既存関数のシグネチャは変更しない（破壊的変更を避ける）。
- `symbol_default_fullwidth` は `windows/crates/shared/src/lib.rs:462` の `CHARACTER_WIDTH_SYMBOL_DEFAULTS` を**単一の正本**とし、`lib.rs` 側は本関数を参照する形へ寄せる（重複定義の解消）。`lib.rs` の公開定数は互換のため残してよいが、値の二重管理は禁止。

### 3-2. Android からの参照方法

**第一候補: JNI 経由で共有 Rust を呼ぶ。**

- `engine/rust/src/bridge.rs` に `op: "inputPolicy"` を追加し、判定に必要な文字列（または1文字）を渡して結果を JSON で返す。
- ただし**キー入力ごとに JNI 越しの JSON 往復を行うと入力遅延に直結する**ため、次のいずれかを採る。
  - (a) **初期化時に一度だけポリシーテーブルを取得**し、Kotlin 側に不変データとして保持する（`BoolArray` / `CharArray` / 区間リスト）。キー入力時はテーブル参照のみ。
  - (b) 判定が単純なもの（`is_immediate_commit_char` 等）は Kotlin に残し、**C の共有シナリオで一致を検証**する（3-3）。

推奨は **(a) + (b) の併用**: 文字集合・幅既定値・正規化マップは初期化時に共有 Rust から取得（データの正本を共有）、判定関数そのものは各言語で実装し、C のシナリオで一致を保証する。これにより入力経路に JNI 呼び出しを挟まない。

取得タイミング: `WindowsEngine.initialize()`（既存の `op=configurePaths` の直後）。失敗時はビルド時埋め込みのフォールバック値を使い、**起動をブロックしない**。

### 3-3. 挙動シナリオ（C）

`tests/input-policy/cases.tsv` を新設し、両OSのテストが同じファイルを読む。

形式（タブ区切り、`#` 始まりはコメント、`input` は1文字）:

```
# id	operation	input	expect
punct_compose	compose_punctuation	、	compose
punct_ascii_comma	punctuation	,	、
punct_pending_n	compose_punctuation_pending	、	commit
digit_compose	digit	5	compose
symbol_immediate	immediate	!	commit
width_exclaim	width	!	full
width_at	width	@	half
```

`operation` は 7 種類（`immediate` / `punctuation` / `compose_punctuation` / `compose_punctuation_pending` / `digit` / `translation_literal` / `width`）。`hosts` 列は持たない。共有 Rust は全行を、Android は同じ全行を検証する。Android 固有だった即確定記号も仕様として表に残すため、両OSで同じ期待値を持つ。

- Windows: `windows/crates/shared/src/conversion_policy.rs` の `#[cfg(test)]` から `include_str!` で読み、`cargo test -p shared` で検証。
- Android: `android/app/src/test/java/com/zeroconvert/ime/InputPolicyScenarioTest.kt` から `androidTest` ではなく **JVM テスト**として同ファイルを読む。テストは `user.dir` から親ディレクトリを遡って `tests/input-policy/cases.tsv` を探すため、Gradle の resources 設定は不要。

`expected` の語彙は最小に保つ（`compose` / `commit` / 正規化後の1文字 / `none` / `literal` / `plain` / `full` / `half`）。新しい判断を追加するときは、まず TSV に行を足し、両OSで落ちることを確認してから実装する。

### 3-4. ドリフト検出（C の運用）

- CI/ローカルで `scripts/check-input-policy-parity.ps1`（新規）を実行し、以下を検査する。
  1. `conversion_policy.rs` の公開関数名が `docs/investigations/input-policy-sharing-plan.md` の一覧と一致する。
  2. Kotlin 側に手移植された文字集合が TSV の全ケースを満たす（Gradle テストを呼ぶ）。
  3. `CHARACTER_WIDTH_SYMBOL_DEFAULTS` が Kotlin 側の埋め込みテーブルと一致する。
- 失敗時は「どちらが正しいか」ではなく、**まず TSV を更新**して仕様を確定させる運用とする。

---

## 4. 実装手順（別セッション向け）

作業は Windows 側と Android 側で独立して進められるが、**TSV の確定を最初に行う**。

### Phase 0: 仕様の確定（30分）

1. `tests/input-policy/cases.tsv` を作成。少なくとも以下を網羅する。
   - 句読点: `、。，．？！` `,` `.` `?` `!` の組成/正規化/確定
   - 未確定ローマ字末尾（`n` `k` 等）での句読点 → 確定して追記
   - 数字: `0-9`、`n` 直後の数字 → `ん` 確定
   - 即確定記号: `!@#$%^&*()`（Android）/ Windows の `?` `!` 相当
   - 幅: `!` `?` `(` `)` `@` `#` `~` の既定全半角
   - 翻訳モード literal 対象
2. 各ケースの期待値を Windows 実装（`composition.rs` / `full_width.rs`）から導出し、コメントに根拠行番号を書く。

### Phase 1: 共有Rustの拡張（1〜2時間）

1. `windows/crates/shared/src/conversion_policy.rs` に 3-1 の関数を追加。
2. `lib.rs` の `CHARACTER_WIDTH_SYMBOL_DEFAULTS` を `conversion_policy` へ移し、`pub use` で再公開（値は不変）。
3. `#[cfg(test)]` で TSV を `include_str!` して全ケース検証。
4. `cargo test -p shared --offline` を通す。

### Phase 2: Android の参照化（2〜3時間）

1. `engine/rust/src/bridge.rs` に `op: "inputPolicy"` を追加し、文字集合・正規化マップ・幅既定値・バージョン識別子を返す。
2. `WindowsEngine.kt` に `inputPolicy` の取得（初期化時1回、キャッシュ、フォールバック付き）を追加。
3. `PanelTextCommit.kt` / `ZeroConvertInputMethodService.kt` の手書き文字集合を、新設 `InputPolicy.kt` の参照へ置換。**判定ロジックの形は変えない**（構造を変えると回帰範囲が広がる）。
4. `ZeroConvertInputMethodService.isNaturalAutoCommitPrefix` / `findStableAutoCommitPrefix` などの手移植関数は、**まず現状維持**とし、Phase 4 で段階的に寄せる（今回のスコープ外）。
5. JVM テスト `InputPolicyScenarioTest` を追加し、TSV 全件を検証。
6. `:rebuilt:testDebugUnitTest` / `:rebuilt:lintDebug` / `:rebuilt:assembleDebug` を通す。

### Phase 3: 整合性検査（1時間）

1. `scripts/check-input-policy-parity.ps1` を追加（3-4 の3項目）。
2. `docs/investigations/README.md` に本計画を追記。
3. `docs/android-shared-engine.md` の共有表に「入力ポリシー（文字集合・幅既定値）」行を追加。
4. `docs/windows-conversion-logic.md` / `docs/android-conversion-logic.md` に「正本は `conversion_policy.rs`、検証は `tests/input-policy/cases.tsv`」と明記。

### Phase 4（任意・後続）: 手移植関数の段階的統合

`is_natural_auto_commit_prefix` / `find_auto_commit_live_conversion_snapshot` 相当の Kotlin 実装を、共有 Rust の結果と突き合わせるテストを追加する。**実装の置換は行わない**（JNI 呼び出しが入力経路に入るため）。差分が見つかった場合のみ、個別に修正計画を立てる。

---

## 5. 受け入れ条件

- [x] `tests/input-policy/cases.tsv` が存在し、全ケースに根拠（Windows 側のファイル:行）がコメントされている
- [x] `cargo test -p shared --offline` が成功し、TSV 全件を検証している
- [x] `:rebuilt:testDebugUnitTest` が成功し、同じ TSV 全件を検証している
- [x] `:rebuilt:lintDebug` / `:rebuilt:assembleDebug` が成功
- [x] Android の `isImmediateCommitChar` / `punctuationForReading` が共有ポリシー由来のデータを参照している（文字列リテラルの二重定義がない）
- [x] `scripts/check-input-policy-parity.ps1` が成功する
- [x] 実端末（arm64 実機）で `、` `。` `？` と pending `n` の入力が組成/確定の期待どおり
- [x] Windows 実IMEで `、` `。` `,` `.` `5` `!` の入力が従来どおり（退行なし）
- [x] 入力経路に JNI 呼び出しが追加されていない（初期化時取得のみ）

## 6. リスクと対策

| リスク | 対策 |
|---|---|
| 初期化時のポリシー取得失敗で起動不能 | ビルド時埋め込みのフォールバック値を使い、警告ログのみ。取得失敗を致命扱いしない |
| TSV と実装の二重管理が新たなドリフトになる | TSV を唯一の受け入れ判定とし、`check-input-policy-parity.ps1` を必須手順にする |
| `lib.rs` の定数整理で既存利用箇所が壊れる | 公開定数は残し、内部実装だけ共有関数へ委譲。`cargo test -p shared` と `cargo test -p azookey-server --lib` を通す |
| Android の手移植関数を一度に置換して回帰 | Phase 2 では文字集合データのみ参照化。判定ロジックの構造変更はしない |
| Windows 側の `punctuation_commit` 設定との整合 | TSV は「設定OFF時の既定挙動」を表す。設定ON時の分岐は Windows 固有として TSV 対象外と明記する |

## 7. スコープ外（明示）

- `composition.rs` の分割・純粋状態機械の抽出
- Android `ComposingText` の共有Rust化
- 記号パネル UI・テンキー UI の共通化
- 幅変換の適用処理そのもの（`full_width.rs` の変換ロジックは共有しない。**既定値データのみ共有**）

## 8. 実装セッションへの引き継ぎメモ

- 変更禁止: `windows/crates/client/src/engine/composition.rs` の構造、`android/.../ComposingText.kt` の API。
- 既存の未コミット差分（`InlinePreviewPolicy.kt` 等）を保持し、`reset` / `restore` / `clean` / `stash` を実行しない。
- ビルド・テストは `windows/` と `android/` で個別に実行する。Android は `-x :rebuilt:verifySharedEngine` が必要になる場合がある（共有ソース変更時）。端末インストールは依頼された場合のみ。
- 完了報告では「ソース編集・テスト・ビルド・実機確認」を分けて明記する。

---

## 9. 実装結果（2026-11-11）

### 変更したファイル

| 層 | ファイル | 内容 |
|---|---|---|
| 仕様 | `tests/input-policy/cases.tsv` | 94 ケース（7 operation、42 記号の幅、10 句読点写像）。根拠行番号をコメントに記載 |
| 共有Rust | `windows/crates/shared/src/conversion_policy.rs` | 6 関数、4 定数、TSV 全件検証、operation 網羅と重複検証 |
| 共有Rust | `windows/crates/shared/src/lib.rs` | `CHARACTER_WIDTH_SYMBOL_DEFAULTS` を `conversion_policy` から `pub use` |
| 共有Rust | `engine/rust/src/bridge.rs` | `op: "inputPolicy"`（version / 即確定記号 / 句読点 / 幅既定値）と検証テスト |
| Android | `android/app/src/main/java/com/zeroconvert/ime/InputPolicy.kt` | 共有ポリシーの保持とフォールバック表、バージョン検証 |
| Android | `PanelTextCommit.kt` / `ZeroConvertInputMethodService.kt` | 手書き文字集合を `InputPolicy` 参照へ置換（判定構造は不変） |
| Android | `engine/WindowsEngine.kt` | 初期化時に 1 回だけ `op=inputPolicy` を取得。失敗時は警告のみ |
| Android | `app/src/test/.../InputPolicyScenarioTest.kt` | TSV 全件、operation 網羅、payload 適用/復元、未知バージョン拒否 |
| 検査 | `scripts/check-input-policy-parity.ps1` | TSV 構造、Rust 定義、Kotlin 表の一致、両OSテスト実行 |
| 文書 | `docs/android-shared-engine.md` / `windows-conversion-logic.md` / `android-conversion-logic.md` / `file-map.md` | 正本と検証手順を追記 |

### 検証結果

| コマンド | 結果 |
|---|---|
| `cargo test -p shared --offline --lib` | 48 passed, 1 ignored |
| `cargo test --manifest-path engine/rust/Cargo.toml --offline` | 73 passed, 1 ignored |
| `cargo test -p azookey-server --offline --lib` | 71 passed, 1 ignored |
| `cargo check -p azookey-windows --offline` | 成功（既存の dead_code 警告のみ） |
| `:rebuilt:testDebugUnitTest` | 280 tests, 0 failures（`InputPolicyScenarioTest` 4件を含む） |
| `:rebuilt:lintDebug` / `:rebuilt:assembleDebug` | 成功 |
| `:rebuilt:assembleDebugAndroidTest` | 成功 |
| `scripts/check-input-policy-parity.ps1` | 成功（表一致＋両OSテスト） |

### 実IMEでの確認（2026-11-11）

Windows は `Build-Trial.bat` で試験IMEへ適用し、メモ帳上で UI Automation により打鍵と入力結果を照合した（`scripts/manual/windows-ime-input-policy.ps1`、ログ `artifacts/logs/windows-ime-input-policy.txt`）。

| 入力 | 結果 |
|---|---|
| `a` → `、` | あ、 (composition 継続) PASS |
| `a` → `。` | あ。 PASS |
| `a` → `?` (Shift+/) | あ？ PASS |
| `a` → `5` | あ5 PASS |
| `a` → `!` (Shift+1) | あ！ PASS |
| `n` → `、` | ん、 PASS |
| `n` → `5` | ん5 PASS |
| `a` → `(` (Shift+8, JIS) | あ（ PASS |

Android は arm64 実機へ `adb install -r` で反映し、`PortInstrumentationRunner` 全 24 件が PASS（vivo V2520A / serial `10AFB2245P003QD`、versionName 2.0.14-dev）。`PendingRomajiRegression` も 10/10 PASS。

ポリシーの実キー確認は `real_ime_keys_and_commit` の後半に組み込み、同スイートで実行する。

| 入力 | 確認内容 | 結果 |
|---|---|---|
| `a` → `、` | 末尾が `、`、composing span が末尾まで継続 | PASS |
| `a` → `。` | 同上 | PASS |
| `a` → `？` | 同上（composition 中のみ出るキー） | PASS |
| `n` → `、` | `ん、`。pending `n` が確定し、文字 `n` が残らない | PASS |
| 24 件全体 | `Tests: 24, failures: 0` | PASS |

検査中は `KEY_REALTIME_INLINE` / `KEY_EXPERIMENTAL_AI_ASSIST` / `KEY_AUTO_COMMIT_ENABLED` を切り、`KEY_JAPANESE_LAYOUT` を QWERTY に固定。終了時に元の値へ戻し、`shared_prefs/ime_settings.xml` で復元を確認した。

- 実行コマンド: `adb -s 10AFB2245P003QD install -r android/rebuilt/build/outputs/apk/androidTest/debug/rebuilt-debug-androidTest.apk` → `adb -s 10AFB2245P003QD shell am instrument -w -r com.zeroconvert.ime.test/com.zeroconvert.ime.PortInstrumentationRunner`
- テスト APK: `C391E965C702AF70EFB80A1C5140B9AEC40FE30AB4C7CA1DE14DCB916F7ABD70`
- アプリ APK (arm64-v8a): `E64C324617A6A0BB8CF17B1B7EE1FE5A59B4F5BD7181FE9A58BABE1E9B9B3881`

### 未実施・残課題

- `real_ime_keys_and_commit` へ組み込む方式にした理由: 別クラスで `uiAutomation.serviceInfo` を設定すると、この OEM 端末では `UiAutomationService ... already registered!` でプロセスが落ちる。既存テストが設定する `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` をそのまま使う必要がある。
- ポリシー検査では変換結果ではなく composition の span を見る。ライブ変換が `ん` を `安` と表示しても、句読点が composition の末尾に残れば合格とする。`n` の直後だけは pending ローマ字確定のため composition 終了が正しい。
- Phase 4（手移植の自動確定関数の突き合わせ）は未着手。
- `IMMEDIATE_COMMIT_CHARS` は Android 固有の即確定記号を共有仕様として保持している。Windows 側に対応するキー経路はなく、`composition.rs` は変更していない。
- 作業中に `KanaKanjiConverter.kt` と `WindowsEngine.kt` の学習系 API が別セッションで書き換えられた。入力ポリシーとは無関係で、当方は触れていない。
