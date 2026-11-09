# 翻訳入力の残り改善計画（Windows / Android）

作成日: 2026-11-08

状態: **実装済み（2026-11-08）**。ユーザー確認で項目2は「数字のみ・記号のみは翻訳しない（Windows準拠、翻訳バーは空）」と決定。
実装上の訂正: 選択翻訳ポップアップの現行ソースは `windows/crates/selection-ui/src/lib.rs`
（`windows/crates/client/src/tsf/selection_popup.rs` はモジュール宣言がなくコンパイルされない旧コピー）。

対象は、翻訳入力の両プラットフォーム比較調査（2026-11-08）で挙がった改善項目のうち、
未実装の5件。完了済みの2件（翻訳のデバウンス/取消、翻訳方向設定）は
`docs/investigations/windows-translation-input.md` と `docs/investigations/android-translation-input.md`
に反映済みなので扱わない。

| # | 項目 | 対象 | 優先度 |
|---|---|---|---|
| 1 | コールドスタート待ちの不整合（kind=3と選択翻訳） | Windows | 中 |
| 2 | 数字のみ・記号のみcompositionの翻訳挙動の不一致 | Windows / Android | 中 |
| 3 | 翻訳エラー報告の細分化 | Windows | 中 |
| 4 | 翻訳モードの数字注入経路の一本化 | Android | 低 |
| 5 | PFML3プロンプト重複定義の同期保証 | 共通 | 低 |

パスはリポジトリルートからの相対パス。

---

## 1. コールドスタート待ちの不整合（Windows）

### 現状と問題

翻訳サーバー（llama-server、ポート18092）のモデル読み込み中の待ち方が、経路によって違う。

| 経路 | 準備待ち | 翻訳タイムアウト | 結果 |
|---|---|---|---|
| 翻訳モード（kind=3） | `ipc_service.rs:554-563` で `translation_http::ready()` を200ms間隔・最大30秒待つ | RPC 11秒（`ipc_service.rs:567`） | サーバー側 `translate_to_cancellable`（`translation.rs:90`）が10秒デッドライン内に100ms間隔でリトライ |
| 選択翻訳ポップアップ | `selection_popup.rs:234-241` で `ready()` を150ms間隔・最大30秒待つ | 30秒 | `translation_http::completion` を直接呼ぶ。リトライなしの1回 |

問題は2つ。

1. **kind=3の実効待ちが二重に短い。** クライアントが30秒待っても、RPC先のサーバー側は
   10秒で諦める。モデル読み込みに10秒超かかる環境（初回のディスク読み込み、低スペックGPU）では、
   「準備待ち30秒」が実質10秒しか機能しない。準備完了直後の要求なら問題ないが、
   準備判定と要求の間でタイミングがずれると失敗する。
2. **選択翻訳は準備待ち後にリトライが1回もない。** 準備判定が通った直後の要求は通常成功するが、
   接続が一時的に拒否された場合（サーバー再起動の瞬間など）即座にエラー表示になる。

### 設計

**方針: サーバー側のデッドラインを経路ごとに明示し、選択翻訳にも短いリトライを足す。**
クライアント側の30秒待ちはそのまま使う（入力コールバックを待機させない現状の構造は維持）。

1. `windows/crates/server/src/ai_assist/translation.rs`
   - `translate_to_cancellable` の10秒デッドラインは、kind=3のRPCタイムアウト11秒より
     短いことをコメントで明示する。値自体は変更しない（RPCタイムアウト内に収まっているのが正）。
   - 選択翻訳が使う `shared::translation_http::completion` はリトライを持たないため、
     呼び出し側（`selection_popup.rs` のワーカースレッド）で接続拒否時のみ
     150ms間隔・最大3回の再試行を追加する。翻訳中の長い応答の再試行はしない
     （要求送信が失敗した場合のみ）。
2. `windows/crates/client/src/tsf/selection_popup.rs`
   - 準備待ちループ（`:234-241`）は現状維持。リトライは上記の接続失敗時のみ。
3. ドキュメント: `docs/investigations/windows-translation-input.md` の「起動・適用」節に、
   「準備待ち30秒はクライアント側の上限であり、翻訳要求自体は10秒で諦める」旨を追記する。

### 影響範囲

- `windows/crates/server/src/ai_assist/translation.rs`（コメントのみ）
- `windows/crates/client/src/tsf/selection_popup.rs`（リトライ追加）
- `docs/investigations/windows-translation-input.md`

### テスト方針

- `translation.rs` の既存テスト（`cancelled_translation_returns_none_without_waiting_for_the_deadline` 等）が
  通ること: `cargo test -p azookey-server`。
- `selection_popup.rs` のリトライは、ローカルのTCPリスナーで「最初の2回は接続拒否、3回目で応答」を
  再現するユニットテストを追加する（`translation_http.rs` のテストがリスナー方式なので同じ形にする）。
- 実IME確認: モデル未読み込みの状態で選択翻訳を実行し、読み込み表示→訳文表示が従来どおり
  動くこと。翻訳モードは既存の確認手順（Ctrl+T→入力→Enter）を流用する。

---

## 2. 数字のみ・記号のみcompositionの翻訳挙動の不一致（Windows / Android）

### 現状と問題

翻訳モードで「123」や「1234-5678」のように数字・記号だけを入力したときの挙動が両OSで違う。

- **Windows**: `typo.rs:210-219` の `editable_composition` が
  「読みにかな（`ぁ..ん`）を含む、またはtypo適用済み」を要求するため、
  数字のみのcompositionではkind=3が送られず**翻訳しない**。数字は
  `UserAction::Number` → `AppendText`（`composition.rs:5316-5340`）でcompositionに蓄積される。
- **Android**: `scheduleTranslation`（`ZeroConvertInputMethodService.kt:577`）は
  読みが空でなければ翻訳するため、数字のみでも**翻訳する**（"123" → "百二十三" 等）。

どちらが正というより、**統一する必要があるかを決める**問題。数字のみの入力は
電話番号・数量・日付の入力で発生し、翻訳が走ると「123」が「百二十三」に置き換わって
確定まで気づきにくい。Windowsの「翻訳しない」挙動のほうが利用者の意図に近い可能性が高い。

### 設計（提案）

**AndroidをWindowsに合わせる**: 読みにかな（ひらがな・カタカナ・漢字）を1文字も含まない
場合は翻訳要求を出さない。英字のみの入力（英→日翻訳）は対象外——英字は
`englishComposing` 経路で、かな判定の前に別扱いされているため影響しない。

1. `android/app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt`
   - `scheduleTranslation`（`:577`）の冒頭に、日本語ソースの場合のみ
     「かな・カタカナ・漢字を1文字も含まなければ `cancelPendingTranslation()` して
     IDLEへ戻す」ガードを追加する。判定は `hasJapaneseKana(source)` のような
     プライベート関数に切り出す（`ぁ..ヿ`、`一..鿿` の範囲判定。Windowsの
     `translation.rs:76-79` `translate_cancellable` の `has_japanese` と同じ集合）。
   - 英字ソース（`sourceIsEnglish`）には適用しない。
2. Windows側は変更しない（現状が意図した挙動）。
3. ドキュメント: `docs/investigations/android-translation-input.md` の「入力経路」節に
   「数字・記号のみの読みは翻訳しない（Windowsに揃えた）」を追記する。

### 未確定事項（実装前にユーザー確認）

- **数字のみを翻訳しない方向でよいか。** 逆（WindowsをAndroidに合わせて数字のみも翻訳）は、
  `editable_composition` のかな条件を緩める必要があり、typo補正・先読みの
  eligibility判定（`typo.rs:222` `eligible`）にも影響が波及するため変更量が大きい。
- 数字のみで翻訳しない場合、翻訳バーには何を表示するか。
  提案: 空のまま（翻訳対象として扱わない）。エラーメッセージは出さない。

### 影響範囲

- `android/app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt`
- `docs/investigations/android-translation-input.md`

### テスト方針

- `android/app/src/test/java/com/zeroconvert/ime/` に、翻訳モードで数字のみ入力したとき
  翻訳状態がIDLEのまま変わらないことを確認するテストを追加する
  （既存の `ComposingTextTest.kt` の数字テストと同じファイル群に追加）。
- 既存テスト: `:app:testDebugUnitTest` が全件通ること。
- 実IME確認: 翻訳モードで「123」と入力→翻訳されないこと、「123あ」と入力→翻訳されること。

---

## 3. 翻訳エラー報告の細分化（Windows）

### 現状と問題

Windowsの翻訳モードは失敗理由を1つのboolでしか持たない。

- `typo.rs:19` `pub translation_failed: bool` → `typo.rs:532` で
  `translation_surface(&candidates).is_none()` から設定。
- `composition.rs:2225` で `"translationError"` としてUIへ。
- `windows/crates/ui/src/ruby.rs:484` 以降の翻訳カードは
  「翻訳失敗・入力し直して再試行」の固定文。

失敗理由（タイムアウト／ソース無効／サーバー停止／訳文が上限超過で打ち切り）が
区別できず、利用者は原因を推測できない。Androidは理由別メッセージを持つ
（`ZeroConvertInputMethodService.kt:819-826` `translationErrorMessage`）。

### 設計

**boolを列挙に置き換え、UI文言を理由別にする。** プロトコル（proto）は変更しない
（サーバー→クライアントは「結果なし」で伝わるため、理由の判定はクライアント側の
既知情報で行う）。

1. `windows/crates/client/src/engine/composition/typo.rs`
   - `translation_failed: bool` を `translation_error: Option<TranslationError>` に変える。
     `TranslationError` は `Timeout` / `Unavailable` / `InvalidSource` / `Truncated` の4値。
   - 判定は `typo.rs:532` の周辺で行う:
     - `Unavailable`: `shared::translation_http::ready()` がfalseのまま結果なし
       （要求送信前に判定できる。`ipc_service.rs` の準備待ち30秒超過でも同じ）。
     - `Timeout`: 準備はしていたが結果が期限内に届かなかった。
     - `InvalidSource`: `valid_source` / `editable_composition` を通らなかった場合。
       ただしこれは要求自体が飛ばないため、UIには出さず無音が正。列挙には入れるが
       現状の経路では設定されない。
     - `Truncated`: サーバー応答の `stop_type == "limit"` は `output()`（`translation.rs:57-63`）で
       Noneになるため、クライアントからは通常の失敗と区別できない。
       **区別するにはprotoか応答JSONの拡張が必要**なので、初期実装では
       `Timeout` / `Unavailable` の2値に絞り、`Truncated` は将来拡張とする。
2. `windows/crates/client/src/engine/composition.rs`
   - `:2225` の `"translationError"` をboolから文字列（`"timeout"` / `"unavailable"` / `""`）へ。
3. `windows/crates/ui/src/ruby.rs`
   - `:484` 以降の翻訳カードで、エラー種別ごとに文言を切替:
     - `timeout` → 「翻訳がタイムアウトしました。入力し直して再試行してください」
     - `unavailable` → 「翻訳サーバーを準備できませんでした。しばらく待ってから再試行してください」
   - 文言はAndroid（`translationErrorMessage`）と揃える。
4. ドキュメント: `docs/investigations/windows-translation-input.md` にエラー文言を追記。

### 影響範囲

- `windows/crates/client/src/engine/composition/typo.rs`
- `windows/crates/client/src/engine/composition.rs`
- `windows/crates/ui/src/ruby.rs`
- `docs/investigations/windows-translation-input.md`

### テスト方針

- `typo.rs` の既存テストに、`translation_error` の設定経路（結果なし→Timeout、
  準備false→Unavailable）を確認するテストを追加。
- `cargo test -p azookey-client` と `Build-Trial.bat -NoPause` でビルド・適用。
- 実IME確認: 翻訳サーバーを停止した状態でCtrl+T→入力→「翻訳サーバーを準備できませんでした」
  が表示されること。サーバー起動中に10秒超の長文でタイムアウト文言が出ることは
  手動確認が難しいため、ユニットテストで代替する。

---

## 4. 翻訳モードの数字注入経路の一本化（Android）

### 現状と問題

翻訳モードでの数字の読みへの反映が、キーボード種別によって2経路に分かれている。

| キーボード | 経路 | 挙動 |
|---|---|---|
| QWERTY | `handleChar`（`:1742`）→ `isTranslationLiteralChar`（`:158`、数字を含む）→ `appendTranslationLiteral` | 数字が読みへDIRECT挿入され、`scheduleTranslation` が走る |
| 12キー | `FlickKeyboardView.kt:192` key1 → `onKanaInput` → `handleKana`（`:1813`） | 「、」「。」「？」「！」のみliteral（`:1819-1822`）。数字は通常のcomposition経路（`:1824` 以降）へ入り、`triggerDebouncedConversion` → 翻訳モードなら `scheduleTranslation` で翻訳される |

12キーの数字はcomposition経路なので、数字が変換対象になり（例: 「1」→候補に「①」等）、
翻訳ソースの読み（`composingText.convertTarget`）にも反映される。結果として翻訳は動くが、
QWERTYの「リテラルとして読みに注入」挙動と内部状態が異なり、
変換候補の表示・自動確定の挙動が数字の前後で揃わない。

前回の修正（`isTranslationLiteralChar` 追加、コミット `e5a6559` 以降の作業）で
QWERTY側はWindowsと同じ「数字もcompositionに含める」挙動に寄せた。
12キー側は通常経路のままなので、**翻訳モード中の12キー数字もliteral注入へ統一する**。

### 設計

1. `android/app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt`
   - `handleKana`（`:1813`）の翻訳モード分岐（`:1819-1822`）を拡張し、
     `kana.length == 1 && kana[0].isDigit()` の場合も `appendTranslationLiteral(kana)` へ
     回す。フリック確定で数字が1文字ずつ届く前提（`FlickKeyDef` のcenter/サブ文字は1文字）。
   - `isTranslationLiteralChar`（`:158`）はChar用なので、handleKana側では
     `kana.singleOrNull()?.let { isTranslationLiteralChar(it) } == true` の形で再利用する。
     「、」「。」「？」「！」の個別列挙をこの判定に置き換える。
2. 影響確認: 12キーの数字がcompositionに入らなくなるため、翻訳モード中の
   変換候補表示・自動確定が数字で発火しなくなる。これはQWERTY側と同じ挙動で、
   翻訳モードでは意図した挙動（読みはリテラル、変換はしない）。
3. ドキュメント: `docs/investigations/android-translation-input.md` の「入力経路」節に
   「翻訳モード中の数字はQWERTY・12キーともliteral注入で読みへ反映する」を追記。

### 影響範囲

- `android/app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt`
- `docs/investigations/android-translation-input.md`

### テスト方針

- `android/app/src/test/java/com/zeroconvert/ime/ComposingTextTest.kt` に、
  12キー経路を想定した `handleKana("1")` が読みへ「1」を追加し、
  変換候補を経由しないことを確認するテストを追加。
- 既存テスト: `:app:testDebugUnitTest` 全件、`:app:lintDebug`。
- 実IME確認: 12キーで翻訳モードに入り、「1」→読みに数字が入り翻訳されること、
  「さん1」のような混在でも翻訳ソースが正しいこと。

---

## 5. PFML3プロンプト重複定義の同期保証（共通）

### 現状と問題

PFML3翻訳プロンプトの文字列が2箇所に重複定義されている。

- Windows（Rust）: `windows/crates/server/src/ai_assist/translation.rs:50-52` `prompt()`。
  `:6` のコメントに "Keep in sync with shared::TRANSLATION_SERVER_PORT. The shared
  conversion engine compiles this module on Android" とある。
- Android（Kotlin）: `android/app/src/main/java/com/zeroconvert/ime/translation/TranslationText.kt:46-52` `prompt()`。

Rust側の `translation.rs` は `#[path]` でAndroid共有エンジン
（`engine/rust/src/lib.rs`）にもコンパイルされるが、AndroidのKotlin側は
`TranslationText.kt` の実装を使うため、実質2実装が並存する。
片方だけプロンプトを変えると、もう片方の翻訳品質が静かに劣化する。
`scripts/prepare-shared-engine.mjs` のfingerprintは `ai_assist` ディレクトリの
変更を検知するが、Kotlin側の `TranslationText.kt` との不一致は検知しない。

### 設計

**実行時の単一化はせず、ビルド時・テスト時の同期検査を入れる。**
（プロンプトをRustからKotlinへFFIで渡す、または共有リソース化する案は、
共有エンジンのビルド構成変更が大きく、初期実装では見送る。）

1. **期待値テストによる固定**: 両実装に、プロンプト文字列の完全一致を期待する
   テストを置く。期待値は同一のリテラル文字列とし、片方を変えると両方のテストが
   失敗するようにする。
   - Rust: `translation.rs` の `mod tests` に、`prompt("会議は明日です。", "en")` の
     完全一致テスト（既存 `:144` は `body["prompt"]` の一致確認なので、
     リテラル固定のテストを追加）。
   - Kotlin: `android/app/src/test/java/com/zeroconvert/ime/translation/TranslationTextTest.kt`
     （新規または既存テストファイルへ追加）に同じリテラルとの一致テスト。
2. **コメントの相互参照**: 両ファイルの `prompt()` 直前に、
   「もう片方の実装パス」と「変更時は両方のテストの期待リテラルを同時に更新する」旨を記載。
   `translation.rs:6` の既存コメントも `TranslationText.kt` への言及に拡張する。
3. **fingerprintの拡張（任意）**: `scripts/prepare-shared-engine.mjs` が
   `TranslationText.kt` の `prompt()` 部分もfingerprintに含め、片方だけ変わったら
   警告を出す。初期実装では1・2で足りるため、実施は任意とする。

### 影響範囲

- `windows/crates/server/src/ai_assist/translation.rs`（テスト・コメントのみ）
- `android/app/src/test/java/com/zeroconvert/ime/translation/TranslationTextTest.kt`（新規）
- `android/app/src/main/java/com/zeroconvert/ime/translation/TranslationText.kt`（コメントのみ）
- `windows/crates/server/src/ai_assist/translation.rs` のコメント

### テスト方針

- `cargo test -p azookey-server` でRust側テストが通ること。
- `:app:testDebugUnitTest` でKotlin側テストが通ること。
- 意図的に片方のリテラルを変えて両テストが失敗することを一度確認し、戻す
  （同期保証が機能することの確認。確認後は元に戻す）。

---

## 実装順序と依存

1. **項目5（プロンプト同期テスト）**: 変更がテスト・コメントのみで最も安全。先に実施して
   土台を固める。
2. **項目1（コールドスタート）**: 選択翻訳のリトライのみで影響小。
3. **項目3（エラー細分化）**: UI文言変更を含むため実IME確認が必要。
4. **項目2（数字のみ翻訳）**: 挙動決定のユーザー確認が前提。
5. **項目4（12キー数字注入）**: Androidのみ。項目2と同じ「数字の扱い」問題なので、
   項目2の方向決定後に実施する。

項目2と4はどちらも「数字の扱いを両OSで揃える」問題のWindows側・Android側であり、
項目2の方向決定（Windowsに合わせるかAndroidに合わせるか）を先に確定させること。

## 未確定事項のまとめ

| 項目 | 未確定事項 |
|---|---|
| 2 | 数字のみ・記号のみを翻訳しない方向（Windows準拠）でよいか。翻訳バーの表示は空でよいか |
| 3 | `Truncated`（訳文上限打ち切り）の区別は初期実装では見送る（proto拡張が必要） |
| 5 | fingerprint拡張（`prepare-shared-engine.mjs`）は任意実施とするか |
