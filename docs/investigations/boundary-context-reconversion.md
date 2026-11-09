# 境界文脈を保持する再変換（自動確定後の変換継続）

## 症状と原因

ライブ変換で先頭部分が自動確定した直後の続き入力で、変換結果の語や境界が確定前と変わる。

Windows 側は確定済みテキストを `leftSideContext`（surface のみ）として Swift に渡していた。Zenzai は残りの読みだけを入力として変換するため、確定済みノードとの接続コストを評価できず、同じ読み列でも別の分割・語が選ばれる。特に「追加」+「情報」のような複合語では、確定済みの「追加」を無視した再分割が起きる。

## 設計

1. クライアント (`composition.rs`) は縮小前の読み `before_reading` から縮小後の読み `after_reading` を剥がし、確定した読みと surface を `conversion_continuation_prefix` で作る。確定読みがかな系で上限内のときだけ採用する。
2. `IPCService` は次回の `AppendText` に `conversion_continuation_reading` / `conversion_continuation_surface` を1回だけ付ける。送信成功後に保留値をクリアする。
3. サーバーは両方そろっているときだけ検証して Swift の `SetConversionContinuation` を呼ぶ。片方だけなら継続をクリアする。
4. Swift は Zenzai 変換時、継続があれば「確定済み読み + 現在の読み」を direct 入力として合成して変換する。候補は確定済み読みの境界でスライスし、消費した prefix の surface が保存 surface と一致することを検証する。辞書ノードが境界をまたぐ場合はノードを分割する。片仮名 ruby は平仮名へ正規化して比較する。
5. `leftSideContext` は保存 surface で終わるときだけその suffix を剥がす。空の context はそのまま使う（アプリが周辺テキストを返さない場合も継続を維持する）。
6. スライスできる候補が1つもないときは合成入力を捨て、現在の残り読みだけで再変換する。

## Windows 実装ファイル

| 層 | ファイル | 内容 |
| --- | --- | --- |
| proto | `windows/crates/shared/service.proto` | `AppendTextRequest.conversion_continuation_reading = 4`, `conversion_continuation_surface = 5` |
| client | `windows/crates/client/src/engine/composition.rs` | `conversion_continuation_prefix`、自動確定・部分確定・候補確定経路での設定/クリア |
| client | `windows/crates/client/src/engine/ipc_service.rs` | `pending_conversion_continuation` の保持、`AppendText` への添付、成功後クリア |
| server | `windows/crates/server/src/main.rs` | 状態、検証、`SetConversionContinuation` FFI、`append_text` での導入、`set_context` での維持/クリア |
| server | `windows/server-swift/Sources/azookey-server/ConversionContinuation.swift` | 型・検証・複合入力・suffix スライス・FFI エクスポート |
| server | `windows/server-swift/Sources/azookey-server/azookey_server.swift` | Zenzai 変換への組み込み、フォールバック、`ClearText` でのクリア |
| FFI | `windows/azookey-server.def` | `SetConversionContinuation` をエクスポート |
| frontend | `windows/frontend/src-tauri/src/ipc.rs` | 新規 proto フィールドを空文字で初期化（設定画面の変換テスト用） |
| tests | `windows/crates/server/src/main.rs`, `windows/crates/client/src/engine/{composition/tests.rs,ipc_service.rs}`, `windows/server-swift/Tests/azookey-serverTests/ConversionContinuationTests.swift` | 検証・スライス・送信・ライフサイクルの回帰テスト |

## ライフサイクル

- 継続の導入: 自動確定・部分確定・候補確定の直後、次回 `AppendText` に1回だけ添付。
- クライアント: `AppendText` 成功後に保留値をクリア。以降の追加送信には付けない。
- `set_context`: context が空なら継続を維持。非空で保存 surface で終わる場合も維持。非空で一致しない場合だけクリア。継続未保存時は従来どおり。
- `remove_text` で composition が空になったとき、`move_cursor`、`adjust_clause_boundary`、`shrink_text`、`start_reconversion`、`clear_text` / composition release、Swift の `ClearText` でクリア。
- スライス不能時のフォールバック変換は継続を再設定しない。

## 実施した検証

環境: `SDKROOT=C:\Users\g0083\AppData\Local\Programs\Swift\Platforms\6.4.1\Windows.platform\Developer\SDKs\Windows.sdk\`、`ZEROCON_CONVERTER_SOURCE` は `artifacts/dependencies/azookey-b12125/...`、`AZOOKEY_LLAMA_BACKEND_DIR` は `artifacts/runtime/windows-b12125-3502b11750bf/llama_cpu`。

```
cd windows
cargo fmt --all
cargo fmt --all -- --check
cargo check --workspace --all-targets
```

`cargo check` は最初、`frontend/src-tauri/src/ipc.rs` の `AppendTextRequest` 初期化に新規フィールドがなく失敗した。空文字の2フィールドを追加して再実行し成功（警告のみ）。

```
.\Build-Trial.bat -NoPause
```

成功（exit 0）。Swift release、Rust release（x64/x86）、frontend をビルドし、`azookey-server.lib` を `windows/crates/server/target/` へコピー。トライアルパッケージ検証後に試験 IME へ8ファイルを適用した。`Apply-Trial.bat` は別実行していない。

```
cargo test --workspace --all-targets
```

`azookey-server` の bin テストが `0xc0000139 (STATUS_ENTRYPOINT_NOT_FOUND)` で起動失敗した。`windows/target/debug/deps/azookey-server.dll` に新シンボルを持たない古い DLL が残っていたため、ビルド済み release DLL を生成物ディレクトリへコピーして再実行した。

- `azookey-server` lib: 71 passed / 1 ignored
- `azookey-server` bin: 91 passed / 1 ignored
- `azookey-windows` lib: 401 passed / 2 ignored
- `rpc_composition_isolation`: 1 ignored（隔離ログオンとサーバーが必要）
- `frontend` lib: 67 passed / 1 ignored、bin: 0
- `launcher`: 11 passed
- `selection-ui`: 2 passed / 1 failed。`tests::transient_connection_failures_are_retried_until_success` が試行回数 3 を期待値 2 として失敗。単独実行では成功したが、モジュール並列実行の再実行では再度失敗した。継続変更とは無関係の既存の不安定テスト。

```
swift test --package-path server-swift -c release --build-system native --no-parallel --filter ConversionContinuation
```

13 tests passed。

```
swift test --package-path server-swift -c release --build-system native --no-parallel
```

128 tests 中 2 件失敗（4 issues）。既知の既存失敗 `latestLearningSelectionIsPrioritizedOnlyForItsReading`（3 issues）と `serverOptionsDisableJapanesePrediction`（1 issue）。継続関連のテストはすべて成功。

```
node scripts/prepare-shared-engine.mjs
node scripts/check-shared-engine-lock.mjs
node scripts/check-shared-engine-inputs.mjs
```

すべて exit 0。`engine/shared-engine.lock.json` を再生成し、converter `c228776b0b869f81ee2a1031ff9dbd679f4b3cd9`、swift `7ac2411e72a9` を確認。manifest は3596入力を網羅。この時点では Android APK/Gradle/端末テストは未実施（Android 側の実施内容は後述）。

## Android 実装（共有層の再利用）

Android の `:rebuilt` は `scripts/prepare-shared-engine.mjs` で `windows/server-swift/Sources` をステージングし、Windows と同じ Swift ソースをコンパイルする。継続の型・検証・複合入力・suffix スライスは `windows/server-swift/Sources/azookey-server/ConversionContinuation.swift` の同一コードであり、Android 用の第二実装はない。Android 側は値の生成、要求への受け渡し、ライフサイクルの管理だけを持つ。

### Android ホスト側の実装ファイル

| 層 | ファイル | 内容 |
| --- | --- | --- |
| host | `android/app/src/main/java/com/zeroconvert/ime/ConversionContinuation.kt` | 確定読みと表記の型、`create` / `derive`。読み64・表記128コードポイント上限。読みはかな U+3041..U+3096、長音 `ー`、繰り返し記号 `ゝ` `ゞ` のみ |
| host | `android/app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt` | `pendingConversionContinuation` の保持。`performAutoCommit` と `commitText` の部分確定で設定し、`triggerDebouncedConversion` / `confirmAsync` で変換要求へ渡す。`clearInput`、`updateComposingDisplayOnCursorMove`、`beginConversionRange`、`refreshConversionRange`、`reconvertSelection`、`adoptAiAssist` の TYPO_CORRECTION 分岐、`revertAiAssist`、`handleDelete` の自動確定巻き戻し、`beginTranslationMode`、および機密入力・空入力で `triggerDebouncedConversion` が検出したときにクリア |
| host | `android/app/src/main/java/com/zeroconvert/ime/engine/WindowsEngine.kt`, `android/app/src/main/java/com/zeroconvert/ime/KanaKanjiConverter.kt` | `requestCandidates` の引数として継続を透過。`fixedSurfacePrefix` の再帰呼び出しは `continuation = null` |
| bridge | `engine/swift/AndroidBridge.swift` | convert 要求ごとに共有側の継続を設定またはクリアする。応答の `continuation` は読み・表記の長さと `accepted` だけを持つ診断用オブジェクト |

### Android 固有の制約

- ブリッジは要求ごとに状態を持たない。同じ残り composition を変換するたびに継続を再送する必要がある。Windows のように1回の `AppendText` に載せて保持させる経路はない。
- `expectedReading` / `cursorUtf16` は現在の composition だけを表す。継続を含む複合読みは共有側で組み立てる。
- 共有側の suffix スライスが候補を現在の composition の単位へ戻すため、`consumedReadingUtf16` と `inputIndexToSurfaceIndexMap` の意味は変わらない。

### 共有側の要求ゲート

次の条件をすべて満たすときだけ継続が合成入力に使われる。満たさない入力はエラーにせず、従来の残り読みだけの変換へ静かにフォールバックする。

- Zenzai 変換が許可されている
- カーソルが composition の末尾にある
- 現在の読みが非空かつ16文字以内
- 確定読み + 現在の読みが48文字以内
- 確定読みと現在の読みがかなのみ
- context が空、または保存 surface で終わる（終わる場合は context からその suffix を剥がす）

### 共有 Rust

`engine/rust/src/lib.rs` はクレート自身を `shared` として解決する（`extern crate self as shared;`）。`ai_assist` の `shared::conversion_policy` は Windows の `windows/crates/shared/src/conversion_policy.rs` を指す単一のポリシーへ解決され、Android 用に複製したポリシーはない。

### 実施した検証（Android・明示継続の時点）

以下は明示継続を実装した時点のビルドで、APK ハッシュと Swift ソース pin はその時点のもの。自動継続を含む最終ビルドの結果は後述する。

- `Build-Android.bat` が exit 0。arm64-v8a と x86_64 の両 ABI をビルドした。
- `:rebuilt:testDebugUnitTest` は 271 件成功、失敗 0。
- `:rebuilt:lintDebug` は成功。警告は既存のもの（`SelectedPhotoAccess` など）。
- 共有 Rust テストは 69 passed / 1 ignored。
- `verifySharedEngine` と Swift pin 検査が成功。`engine/shared-engine.lock.json` を再生成し、swift `3a615ff774aa` を確認した（最終ビルドでは後述の `c301b7134141…`）。
- 最初のビルドは `error[E0433] cannot find module 'shared'` で失敗し、上記の `extern crate self as shared;` で解消した。

debug APK（`:rebuilt:assembleDebug`、ABI 別）:

| ABI | APK | SHA-256 |
| --- | --- | --- |
| arm64-v8a | `android/rebuilt/build/outputs/apk/debug/rebuilt-arm64-v8a-debug.apk` | `82A322BD754ADE567FFF69FCE8E2DCA092840F96B52A36BD47ACA0F32C3A78D4` |
| x86_64 | `android/rebuilt/build/outputs/apk/debug/rebuilt-x86_64-debug.apk` | `91ED20256318320D77167A9F97C245F46348A6ACF64BF0CD413591E99DA70714` |

### Android の制限

- 16文字/48文字のゲートにより、長い確定接頭部は複合変換されない。
- スライス後の候補の suffix ruby が現在の読みより長い場合、`AndroidBridge.swift` の consumed 件数検査（`candidate.correspondingCount` の境界が現在のカーソル位置を超える候補を除外する）で落ちる。
- 継続は自動確定・部分確定後のかなライブ変換だけを対象とする。再変換と翻訳モードは対象外。

## 自動継続（明示的な自動確定を伴わない文途中入力）

自動確定・部分確定を伴わない通常の続き入力でも、左文脈の末尾から確定済みの読みと表記を逆変換し、同じ境界考慮の再変換を行う。明示的な継続（`conversionContinuation`）の要求を先に試し、それが使えないとき（明示状態がない、保存 surface が現在の context の suffix でない、長さ・かな条件を満たさない等）に自動継続を試す。明示が常に勝って自動が決して走らない、という関係ではない。

### 実装

| 層 | ファイル | 内容 |
| --- | --- | --- |
| server | `windows/server-swift/Sources/azookey-server/azookey_server.swift` | `ReconversionReadingPiece`、`ReconversionDictionary.inferReadingPieces`、`get_composed_text_impl` の自動継続ゲート、`initialize` / `load_config` でのキャッシュ無効化 |
| server | `windows/server-swift/Sources/azookey-server/AutomaticConversionContinuation.swift` | 逆変換DPの経路から文脈末尾の確定済み読み・表記を組み立て、`ConversionContinuation` を返す |
| tests | `windows/server-swift/Tests/azookey-serverTests/AutomaticConversionContinuationTests.swift` | 合成辞書による回帰テスト。実辞書バイナリとGPUは不要 |

### ゲートと上限

- Zenzai 変換が許可されている。
- 明示的な継続要求が使えない（明示状態がない、または保存 surface が現在の context の suffix でない等）。
- カーソルが composition 末尾にある。
- 末尾に未完成ローマ字がない。
- context が非空。
- 現在の読みが非空かつ16文字以内のかな。
- 経路復元: `String(context.suffix(n))` を n = 文脈長（最大24）, 12, 6, 3 の順にDPへかけ、最初に全文字を被覆できた経路を使う。結果は context 完全一致の1件キャッシュ（成功・失敗とも）。
- 信頼度: 最良経路と2位の全被覆スコア差が0.5未満なら曖昧として採用しない。
- 末尾からの取り込み: 確定済み表記12文字、読み24文字、現在の読みとの合計48文字を上限とする。句読点・改行・閉じ括弧（`。」』】）)]}｝〉》` など）を含む piece に達したら停止する。
- 戻り値は `context.hasSuffix(surface)` を満たす場合だけ採用する。
- 再構成した surface がかなのみの場合は採用しない。かなだけの接頭部は再構築しても変換を改善できず、漢字変換された場合は surface スライスが一致しないため。

### 無効化

- スライス不能時は `disableAutomaticConversionContinuation(context:)` を呼び、同じ context での再打鍵が二重変換にならないようにする。context が変われば再び有効になる。
- `initialize` の辞書ロード後（成功・失敗とも）と `load_config` のユーザー辞書反映後に `invalidateAutomaticConversionContinuationCache()` で経路キャッシュと無効化 context を破棄する。
- 診断ログには `continuation_auto=true` と既存の長さ項目だけを追加し、表記・読みの本文は出さない。

### 検証（Windows・自動継続を含む最終ビルド）

```
cd windows
swift build --package-path server-swift -c release --build-system native -Xcc -D_CRT_USE_C_COMPLEX_H -Xcxx -D_CRT_USE_C_COMPLEX_H
swift test --package-path server-swift -c release --build-system native --no-parallel --filter "AutomaticConversionContinuation|ConversionContinuation"
swift test --package-path server-swift -c release --build-system native --no-parallel
```

ビルド成功。絞り込みテストは 27 件成功（自動継続14件、既存の明示継続13件）。全体は 142 件で、既知の既存失敗 `latestLearningSelectionIsPrioritizedOnlyForItsReading`（3 issues）と `serverOptionsDisableJapanesePrediction`（1 issue）のみ。新規の失敗はない。

```
cd windows
.\Build-Trial.bat -NoPause
```

成功（exit 0）。Swift release、Rust release（x64/x86）、frontend をビルドし、試験 IME へ適用した。適用先 `ZeroConvertIME-windows/azookey-server.dll` の SHA-256 は `25b41eaef4f4b25b9399b831175ef261559e2236ec59d0e43e0fdaf0ad51e1cb`。適用 DLL の export に `SetConversionContinuation` が含まれることを確認した。実アプリでのキー入力による確認は未実施。

### 検証（Android・自動継続を含む最終ビルド）

- `Build-Android.bat` が exit 0。arm64-v8a と x86_64 の両 ABI をビルドした。
- JVM テストは 271 件成功、失敗 0。
- lint は 0 errors / 29 warnings（既存の警告）。
- `verifySharedEngine` と Swift pin 検査が成功。`engine/shared-engine.lock.json` の `swiftSources` は `c301b7134141…`。
- debug APK の SHA-256: arm64-v8a `398F35E911FEA8DBE8E2720896F5C9CBBF2D3FA9D6A66FC1533FF9CECE91FAF8`、x86_64 `376E38AC28AC7404D357089D6EA77A5CE29B4B2B4878F05F89967755B87AB5A2`。
- 自動継続のための Kotlin 変更は不要だった。Android は既に確定済み左文脈（`cachedLeftContext`）を送っており、エンジンが convert 要求ごとに prefix を自前で導出する。

### Android 側でライフサイクル処理が不要な理由

自動継続は要求ごとに現在の context から導出され、クライアント状態として保持されない。`context.hasSuffix(surface)` と、エンジン側の1件の context キャッシュおよび per-context の無効化で制御されるため、Android に追加のクリア処理は要らない。

## 未検証・残作業

Windows・Android とも、実 IME での打鍵確認は未実施。

### Windows

- 実アプリでのキー入力による IME 動作確認は未実施。試験 IME へのバイナリ適用までで、変換結果の実機確認はしていない。
- 自動継続はクライアントが送るカーソル前の周辺テキスト（最大24文字）に依存する。周辺テキストを返さないアプリでは自動継続は働かない。12文字の surface 上限を超える長い続き文では末尾の12文字だけを使う。
- 上記の Swift 既存失敗2件と `selection-ui` の不安定テストは未修正（対象外）。

### Android

- 端末への APK 適用は未実施。実 IME での打鍵・自動確定・部分確定・巻き戻し・候補確定の確認はしておらず、Android の IME 挙動は端末で未検証。APK ビルドと JVM テストまで。

