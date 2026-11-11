# Androidの矢印キーによる生かな確定と長文入力時クラッシュの調査

2026-11-10。Android版の未確定入力中に画面上の矢印キーを押すと生のかなが確定される現象と、長い入力を変換するとプロセスがネイティブ中断する現象を、現行ソースと既存ログの読解で追跡した記録。

本レポートは調査のみである。ソース修正、ビルド、端末への適用、実機確認はいずれも行っていない。記載した経路はコード上の経路であり、再現や修正効果を測定したものではない。

## 1. 概要

| 現象 | 直接の観測 | コード上の主因 | 検証状態 |
| --- | --- | --- | --- |
| 現象1: 矢印キーでひらがな確定 | 変換プレビューが消え、生のかなが確定する | 画面キー経路が選択所有権を登録せず、`onUpdateSelection` が自分の書き込みを外部移動と誤認して `clearInput()` と `finishComposingText()` を実行する。直前のプレビュー復帰で composing region が生かなになっている | コード読解のみ。再現未実施 |
| 現象2: 長文でクラッシュ | SIGABRT(またはSIGSEGV)でプロセス消滅 | Zenzaiの推論バッチ容量が512固定のまま、プロンプト全体と候補を1バッチで投入する。固定リビジョンの `llama_batch_add` に境界検査がなく、配列外書き込みの後に `GGML_ASSERT` で `abort()` する | コード読解のみ。トークン数未計測 |

両現象とも、x86_64エミュレータのSwiftランタイム不整合により実行検証できていない(4章)。「Snapdragon 8 Elite搭載機なので性能問題ではない」という前提は報告者の申告であり、今回読んだログからはSoCを確認していない。

### パス略記

以降の引用で使う略記。コンバータの行番号は、固定リビジョン `c228776b0b869f81ee2a1031ff9dbd679f4b3cd9` の `batao9/AzooKeyKanaKanjiConverter` における行番号を指す(GitHubから同リビジョンを取得して確認できる)。`engine/build/swift/converter/` 配下は `scripts/prepare-shared-engine.mjs` が生成するステージ用の複製で、Git管理外である。パッチを適用した複製では行番号がずれるため、以下の引用はパッチ前の固定リビジョンを基準にする。

| 略記 | 実パス |
| --- | --- |
| Service.kt | `android/app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt` |
| KeyboardView.kt | `android/app/src/main/java/com/zeroconvert/ime/ComposeKeyboardView.kt` |
| Flick.kt | `android/app/src/main/java/com/zeroconvert/ime/FlickKeyboardView.kt` |
| EditorState.kt | `android/app/src/main/java/com/zeroconvert/ime/ComposingEditorState.kt` |
| ComposingText.kt | `android/app/src/main/java/com/zeroconvert/ime/azookey/input/ComposingText.kt` |
| ZenzContext.swift | `Sources/KanaKanjiConverterModule/ConversionAlgorithms/Zenzai/Zenz/ZenzContext.swift` |
| ZenzEvaluator.swift | 同ディレクトリ `ZenzCandidateEvaluator.swift` |
| ZenzPrompt.swift | 同ディレクトリ `ZenzPromptBuilder.swift` |
| zenzai.swift | 同ディレクトリ `zenzai.swift` |
| AndroidBridge.swift | `engine/swift/AndroidBridge.swift` |
| llama-batch.cpp | `android/app/src/main/cpp/llama.cpp/src/llama-batch.cpp` |
| llama-context.cpp | `android/app/src/main/cpp/llama.cpp/src/llama-context.cpp` |
| ggml.c | `android/app/src/main/cpp/llama.cpp/ggml/src/ggml.c` |

## 2. 現象1: 矢印キーでひらがな確定

### 2.1 再現条件

- ライブ変換などで変換プレビューが表示されている未確定入力がある。
- 画面上の ◀ / ▶ をタップまたは長押しする。ハードウェアキーボードの矢印ではない。
- 次のいずれかで顕在化しやすい。
  - 変換プレビューの末尾が、移動後の残り読みsuffixと一致せず、プレビュー全体が読みへ戻された直後。
  - 右移動でキャレットが合成文字列の末尾に到達した直後(所有権の記録が残らない経路に入る)。
  - 入力先アプリが `onUpdateSelection` で変換範囲を `-1` として通知する、または古い範囲を通知する。
  - 連続押下で選択通知の処理が遅れ、期待履歴が追いつかない。

### 2.2 因果連鎖

1. 画面上の矢印はサービスを直接呼び、所有権を登録しない。`KeyboardView.kt:499-500`(日本語12キー)、`:525-526`(日本語QWERTY)、`:560-561`(英語12キー)、`:586-587`(英語QWERTY)、`:611-612`(数字)、`:676-681`(記号)はいずれも `{ imeService.handleCursorLeft() }` と `{ imeService.handleCursorRight() }` を渡すだけである。長押しは `RepeatingIconButton`(`KeyboardView.kt:827-869`)が押下時に1回実行し、`initialDelayMs` 経過後に `repeatIntervalMs` 間隔で繰り返す。呼び出し側は `KeyboardView.kt:1124-1136`、`:1138-1151`、`:1381-1393`、`:1395-1407`、`:1477-1484`、`:1488-1495`、`:2586-2598`、`:2600-2613` と `Flick.kt:610-622`、`:685-697`、`:988-1000`、`:1063-1075`、`:1340-1352`、`:1387-1399` で、いずれも `initialDelayMs = 300L`、`repeatIntervalMs = 50L` を指定する。

2. 所有権を登録するのはハードウェアキーだけである。`Service.kt:2422-2430` の `onKeyDown` が対象キーを `managedCursorKeys.add(keyCode)` し、`:2432-2435` の `onKeyUp` が削除する。`managedCursorKeys` の参照は宣言 `:1489` を含めて4箇所だけで、画面キー経路からは触られない。

3. ハンドラは内部の読みカーソルを動かす。`Service.kt:2388-2403` の `handleCursorLeft` と `:2405-2420` の `handleCursorRight` は、`ComposingText.moveCursorFromCursorPosition`(`ComposingText.kt:285-290`)で `convertTargetCursorPosition` を更新し、移動量が0でなければ `updateComposingDisplayOnCursorMove()` を呼ぶ。

4. その関数は変換プレビューを維持できる場合だけ維持する。`Service.kt:2437-2454` は `suffix = composingText.convertTarget.drop(convertTargetCursorPosition)` と `composingEditor.preview` を比較し、`currentPreview.endsWith(suffix)` が成り立つ場合だけプレビューを残す。成り立たない場合は `previewToSet = composingText.convertTarget`(`:2447-2451`)となり、合成文字列全体が生の読みへ戻る。その後 `setComposingPreview`(`:2452`)と `triggerDebouncedConversion`(`:2453`)を呼ぶ。

5. `setComposingPreview`(`Service.kt:1500-1520`)は、キャレットが末尾になる場合(`position == text.length`、`:1504-1505`)にだけ `setManagedComposingText`(`:1494-1498`)へ分岐する。この経路は `connection.setComposingText(text, 1)` の後に `composingEditor.recordPreview(...)` を呼ぶ。`recordPreview`(`EditorState.kt:31-36`)は `currentSelection = null` を設定するだけで、`pendingSelections` に何も積まない。つまりこの書き込みは所有権の記録を残さない。末尾以外では `ComposingEditorState.write`(`EditorState.kt:59-81`)を使い、移動1回につき2件の期待値(`:75`、`:78`)を積む。

6. 期待値の保持は固定長である。`pendingSelections` は `ArrayDeque`(`EditorState.kt:23`)で、`expect` が16件を超えた分を先頭から捨てる(`:42-47`)。`ownsSelection`(`:49-57`)は一致した位置までを取り除き、見つからない場合は `currentSelection` 単体とも照合する(`:56`)。移動1回で2件積むため8回で上限に達し、50ms間隔の自動連打は毎秒20回なので、選択通知の処理が遅れると約400msで古い期待値が押し出される。ただし直近の期待値は `currentSelection` にも残るため、押し出し単独では直ちに所有権は失われない。決定的な穴は手順5の `recordPreview` 経路であり、これは直近の記録も消す。

7. `onUpdateSelection`(`Service.kt:1622-1655`)は、所有権が確認できない通知を破壊的に扱う。`ownedSelection` は `:1638`。`isInsideComposing`(`:1641-1642`)は `candidatesStart >= 0` を要求する。`isExternalMove`(`:1643-1644`)は `!ownedSelection && managedCursorKeys.isEmpty() && !isInsideComposing` かつ範囲外または範囲未通知の場合に真となる。真なら `:1645-1648` で `clearInput()` と `currentInputConnection?.finishComposingText()` を実行する。`clearInput()`(`:2895-2935`)は内部状態と `composingEditor.clear()`(`:2916`)を初期化するが、すでにエディタへ書いた文字列は戻さない。`finishComposingText()` は composing region を確定する。手順4で生かなを書いていれば、その生かながそのまま確定される。これが症状の直接の生成点である。

8. 二次的な生かな確定経路として `confirmAsync`(`Service.kt:2520-2595`)がある。`:2536` で `currentSurface = composingText.convertTargetBeforeCursor`(読みのカーソル前)を保持し、`:2575-2577` で候補が空なら `candidate?.text ?: currentSurface` を確定する。`:2582-2588` では例外時に `commitText(currentSurface + trailingText)` を実行する。候補ゼロ件やエンジン例外が、利用者に見えないまま生かな確定になる。

### 2.3 端末依存に見える理由

- `isInsideComposing` が真の間は、所有権が失われても破壊されない。入力先アプリが常に正しい変換範囲を返す場合、症状は出にくい。
- 逆に、変換範囲を省略(`-1`)する、古い終端を返す、範囲を片側だけ返すアプリでは、同じ操作でも外部移動と判定されやすい。
- 手順4のプレビュー復帰が走るかどうかは、読みとカーソルの位置関係で決まる。同じ矢印操作でも語の途中か末尾かで結果が変わる。
- 連打の速さと選択通知の処理遅延で、期待値の残り方が変わる。

したがって「特定端末でだけ起きる」のではなく、「入力先アプリの `onUpdateSelection` の範囲の返し方と、カーソルが末尾かどうかの組み合わせ」に依存すると読める。既存の修正記録(`android-cursor-arrow-handling.md`、`android-cursor-ownership.md`)も、範囲終端の遅れと範囲省略を許容する方向で所有判定を広げている。今回の調査はその延長線上にある。

### 2.4 設計上の緊張

エディタの composing region は変換後の漢字を持つことがあり、内部の読みは常にかなである。両者の位置は1対1で対応しない。

そのため「エディタの選択通知が自分の書き込み由来か」を、文字列ではなく選択位置と範囲で判定する必要がある。現行実装では、その判定を16件の履歴と `managedCursorKeys` に依存させている。しかし `setManagedComposingText` は履歴を積まず、画面キーはキー集合を立てない。結果として、所有権の確認は「たまたま一致する」ことに依存し、外れると `clearInput()` と `finishComposingText()` という復旧不能な後始末が走る。ここが設計上の緊張点である。

## 3. 現象2: 長文でクラッシュ

### 3.1 発生機構

| 層 | 位置 | 内容 |
| --- | --- | --- |
| 固定リビジョン | `windows/scripts/prepare-modern-llama.py:21` | `CONVERTER = 'c228776b0b869f81ee2a1031ff9dbd679f4b3cd9'` |
| Androidへの供給 | `scripts/prepare-shared-engine.mjs:18-26` | Windowsと同じ準備処理を呼び、同じ固定リビジョンをステージする |
| コンテキスト設定 | ZenzContext.swift:51-54 | 固定リビジョンは `n_ctx = 512`、`n_batch = 512`。`converter_patch.py:26-29` の適用後は `n_ctx = 1024`、`n_seq_max = 2`、`kv_unified = true` となり、`n_batch` は512のまま |
| バッチ確保 | ZenzContext.swift:149 | `llama_batch_init(512, 0, 1)`。容量512で固定 |
| バッチ書き込み | ZenzContext.swift:211-220 | `batch.token[Int(batch.n_tokens)]` 等へ無条件に書く。境界検査なし |
| 無効なガード | ZenzContext.swift:152-155 | `n_kv_req = tokens.count + (Int(n_len) - tokens.count)` は常に512。`n_kv_req > n_ctx` は成立しない |
| 評価呼び出し | ZenzEvaluator.swift:66-68 | `let tokens = promptTokens + candidateTokens` を1回の `evaluationLogits` へ渡す |
| プロンプト | ZenzPrompt.swift:87、:90、:104、:106 | 左文脈はトリムするが、`input`(読み)はトリムしない |
| ネイティブ確保 | llama-batch.cpp:1091-1119 | `malloc` は `n_tokens_alloc = 512` を前提とする |
| ネイティブ検査 | llama-context.cpp:1896 | `GGML_ASSERT(n_tokens_all <= cparams.n_batch)` |
| アサートの展開 | `android/app/src/main/cpp/llama.cpp/ggml/include/ggml.h:287-288` | `GGML_ASSERT` は `GGML_ABORT` へ展開し、`ggml_abort` を呼ぶ |
| 中断 | ggml.c:253-273 | `ggml_abort()` が最後に `abort()` を呼ぶ |

処理の流れは次のとおり。

1. 固定リビジョンの `ZenzContext` は、読みと候補の全体を `tokens` として1回のデコードへ渡す(`ZenzEvaluator.swift:66-68`)。`azookey_server.swift:1490` が `inferenceLimit: 5` を渡し、`zenzai.swift:171-188` の評価ループが最大5回まで評価するため、同じ過大バッチが複数回発生し得る。
2. プロンプト長は合成文字列の長さに比例する。`ZenzPrompt.swift:87` と `:90` は左文脈を `mode.maxLeftSideContextLength` で切り、未指定なら40文字(`ZenzPrompt.swift:20`)、`azookey_server.swift:1493-1497` は長さを指定せずに `.v3` を組む。一方 `input` は `:104` と `:106` でそのまま連結される。
3. `tokens.count` が512を超えると、`llama_batch_add` が `malloc` 済み配列の範囲外へ書く。`n_tokens` も512を超えて進む。
4. 配列外書き込みでヒープを壊してSIGSEGVになるか、そのまま `llama_decode` へ進んで `GGML_ASSERT(n_tokens_all <= cparams.n_batch)` に達し、`ggml_abort()` から `abort()` でSIGABRTになる。
5. `ggml_set_abort_callback`(`ggml.c:247-251`)は登録でき、実際に呼ばれる(`:264-266`)が、その直後の `abort()`(`:272`)は無条件である。コールバックでログは取れても中断は防げない。

`windows/scripts/converter_patch.py:26-29` は `n_ctx` を512から1024へ上げ、`n_seq_max = 2` と `kv_unified = true` を追加するが、`n_batch` は512のまま据え置き、`llama_batch_init(512, 0, 1)` も変更しない。利用可能なコンテキストだけが増え、1バッチの容量は増えていない。

このため、仮に `n_ctx` に対する検査を足しても、513から1024トークンの範囲は通過してしまい、バッチ容量の超過は防げない。検査は `n_batch` を基準にするか、プリフィルを分割する必要がある。

IME層には上限がない。`AndroidBridge.swift:81-82` が制限するのは `elements.count <= 4096` という転送要素数だけで、トークン数や読みの長さではない。4096要素の合成は、読みが数千文字に相当し得る。

一方、キャンセル時の後始末は既にある。`AndroidBridge.swift:135-142` は中断を検出すると `converter.stopComposition()` と `normalNBestSupplementConverter.stopComposition()` で両セッションをリセットする。これは中断後にKVキャッシュが半端に残る場合の正しい復旧だが、バッチ容量の超過そのものは防がない。

### 3.2 上限の見積り

プロンプトはおおむね「BOS + タグ + 左文脈(トリム後)+ カタカナの読み + 出力タグ + 候補文字列」で構成される。`ZenzEvaluator.swift:66` は読み由来の `promptTokens` と候補の `candidateTokens` を足すため、両方が上限に効く。

4.4 で実測した。読み `キョウハイイテンキデスネ` の繰り返しでは、480文字までは512トークン未満で通過し、600文字で543トークンに達して停止した。

| 文字数 | トークン数 | 結果 |
| --- | --- | --- |
| 480 | 512未満 | 正常復帰 |
| 540 | 未到達(文字数上限で拒否) | `engine limits` エラー |
| 600 | 543 | SIGILL で停止 |

したがって単一の文字数ではなく、「プロンプト全長 + 候補長が512トークンに達する点」を基準に考えるべきである。この読みでは概ね500から600文字が境界であり、文字数の閾値は文章の内容に依存する。かな1文字あたり1トークンという前提は実測の範囲でほぼ成立しているが、他の文章種別では未計測である(7章)。

### 3.3 既存ログとの関係

今回読んだローカルログには、この `GGML_ASSERT` 経路の記録はない。

- `.local/android-arrow-crash-investigation/logcat-after.log` はx86_64エミュレータの記録で、`libzenz_jni.so` のロードに失敗し、`WindowsEngine.nativeLoadBackends` で `UnsatisfiedLinkError` になっている(`:561-589`)。エンジンが初期化されないため、Zenzaiのデコードまで到達しない。
- `.local/android-arrow-crash-investigation/instrumentation-baseline.log` では `native_assets_and_model_load`、`shared_windows_conversion_and_input_snapshots`、`shared_cursor_prefix_with_and_without_ai` が開始され、いずれも `PASS` 行を出さず、コルーチンの継続フレームを stream に出力して status code 0 で閉じている(`:19-36`)。その後 `native_dictionary_morphology` を開始した直後に `Process crashed.` で終わっている(`:43-47`)。Zenzaiの長文デコード以前で止まっている。
- `.local/android-rebuild-probe/native-crash.log` と `.local/android-mode-switch-20261026/crash-buffer.log` のSIGTRAPは、後述の別種クラッシュである。

つまり現象2は、コード上の経路は特定できたが、手元のクラッシュログとはまだ結び付いていない。

### 3.4 別種クラッシュ(SIGTRAP)の切り分け

次の2件はSwiftのアクター/エグゼキュータ分離違反によるSIGTRAPであり、現象2とは別種である。混同しない。

| ログ | 時刻 | スレッド | 主なフレーム |
| --- | --- | --- | --- |
| `.local/android-rebuild-probe/native-crash.log:91-159` | 2026-10-24 23:17:50 | `ZeroCon-shared-` | `dispatch_assert_queue`(`:118`)、`swift_task_isCurrentExecutorWithFlags`(`:120`)、`zerocon_swift_request+80`(`:121`)、`Java_com_zeroconvert_ime_engine_WindowsEngine_nativeSwift`(`:122`)、Kotlinの `WindowsEngine.checkedSwift`(`:128`)と `WindowsEngine.configure`(`:132`) |
| `.local/android-mode-switch-20261026/crash-buffer.log:11-96` | 2026-10-26 14:36:37 と 15:17:34 | `DispatchWorker` | `KanaKanjiConverterModule.requestCandidates`(`:40`、`:83`)、`swift_job_run+152`(`:48`、`:91`)、`libdispatch` の各フレーム |

現行の `AndroidBridge.swift` は、JNI入口を `Task { @MainActor in ... }` で包み、呼び出し元のJNIスレッドを `DispatchSemaphore` で待たせる(`:182-199`)。`@globalActor EmbeddedConversionActor` と `MainActor` の別名も定義済み(`:11-14`)。この形なら、Windows由来の `@MainActor` 前提コードはAndroidのUIキューではなく専用アクター上で直列化される。

したがってこのクラスは「修正済みの可能性が高いが未確認」である。理由は、トゥームストーンに `versionCode`、`versionName`、Gitビルド識別子の対応がなく、どのビルドで発生したかを特定できないためである。5.3(c)で対応を挙げる。

## 4. 検証できたこと・できなかったこと

### 4.1 実施した確認と未実施の検証

| 区分 | 内容 |
| --- | --- |
| できた | 本文の全引用を現行ソースで確認した(メインツリー、固定リビジョン `c228776b` の上流ソース、llama.cpp サブモジュール) |
| できた | `managedCursorKeys` の全参照を確認し、画面キー経路から更新されないことを確認した |
| できた | コンバータの固定リビジョン、パッチ内容、`n_ctx` と `n_batch` の不一致を確認した |
| できた | 既存のSIGTRAPログ2件を確認した |
| できた | x86_64の `dlopen` 失敗を修正し、エミュレータでネイティブエンジンがロードできることを確認した(4.2) |
| できた | 現象1をエミュレータで再現した(4.3) |
| できた | 現象2をエミュレータで再現し、発生トークン数を実測した(4.4) |
| できなかった | arm64実機での現象1と現象2の再現、および修正効果の確認 |
| できなかった | SIGTRAPクラスが現行ビルドで解消しているかの確認 |
| 未実施 | 5章の改善案の実装と、実装後の回帰確認 |

### 4.2 検証基盤の修正

#### 4.2.1 元の欠陥

x86_64で `libzenz_jni.so` の `dlopen` が次で失敗していた。

```
cannot locate symbol $ss9OptionSetP8rawValuex03RawD0Qz_tcfCTq referenced by libswiftDispatch.so
```

原因は、同梱の `libswiftCore.so` が古いツールチェーン由来、`libswiftDispatch.so` が公式アーカイブ由来という混成である。パッケージング上の経路は次のとおりだった。

- `scripts/extract-android-swift-runtime.py` は `libdispatch.so` と `libswiftDispatch.so` の2ファイルだけを抽出していた。
- `scripts/build-android-shared-engine.ps1` はオーバーレイを先にコピーし、その後に SDK の `*.so` をコピーしていたため、SDK由来の `libswiftCore.so` が残った。

#### 4.2.2 判明した重大な事実

固定アーカイブ `.local/toolchains/swift-6.4.1-RELEASE_android.artifactbundle.tar.gz` は、インストール済みツールチェーンのSDKと**別ビルド**の Swift 6.4.1 である。再パッケージではない。

| 項目 | 公式アーカイブ | インストール済みSDK |
| --- | --- | --- |
| リンカ | LLD 21.0.0 (googleplex 系パス) | LLD 21.1.6 |
| ビルドパス | `swift-android` | `swift-6.4.0-windows-toolchain` |
| 全 `.so` の SHA256 / GNU Build ID | アーカイブ側で一致 | SDK側で一致 |

したがって両者を混ぜると必ず壊れる。実際、`libswiftCore` だけをアーカイブ版にすると `Foundation.JSONDecoder` の内部(`LoadConfig`)で SIGSEGV した。`libFoundationEssentials.so`(SDK BuildId `75d77b9c`)がアーカイブ版 `libswiftCore.so`(BuildId `e5ed7379`)と組み合わされたためである。

さらに、ランタイム全体をアーカイブ版にしても、`libzerocon_swift.so` を再コンパイルしないと不整合が残る。プリビルド体はSDK版Foundation向けにコンパイルされており、アーカイブ版Foundationが公開しない次のシンボルを要求した。

```
$sSSs7CVarArg10FoundationWP
$s20FoundationEssentials10CocoaErrorVs0D0AAWP
```

アーカイブ版Foundationの protocol witness table はSDK版より少ない(`libFoundation` 189 対 681、`libFoundationEssentials` 113 対 876)。

#### 4.2.3 採用した構成

「1 ABI = 1ビルド」を不変条件とした。

| ABI | ランタイムの出所 | Swiftモジュール |
| --- | --- | --- |
| x86_64 | アーカイブの24ライブラリ全体 | アーカイブのSDKペイロード(`engine/build/swift-runtime-sdk/x86_64/`) |
| arm64-v8a | インストール済みSDK(自己整合しているため変更なし) | インストール済みSDK |

x86_64 はアーカイブのSwift SDKペイロード(`usr/lib/swift-x86_64/` のモジュールと `usr/include/`)を展開し、`-resource-dir` をそこへ向けて再コンパイルした。これによりコンパイル時と実行時のFoundationが同一ビルドになる。

変更したファイルは次の3つで、アプリのソースコードは変更していない。

| ファイル | 変更 |
| --- | --- |
| `scripts/check-android-native.py` | ELF `.dynsym` を解析し、パッケージ内のどのライブラリも定義しない `$s` 始まりのSwiftシンボルを検出して失敗させる。従来は `--prune-unused` 指定時にこの検査が飛んでいたため、ビルドが壊れたランタイムで成功していた |
| `scripts/extract-android-swift-runtime.py` | ABIごとに単一ビルドを選択し、`source.json` を出力する |
| `scripts/build-android-shared-engine.ps1` | オーバーレイをSDKコピーの後に適用する。ランタイムのビルドが変わった場合は再コンパイルを強制する |

検証結果は `python scripts/check-android-native.py` が両ABIで exit 0、`python scripts/check-android-apk.py` も exit 0 である。

### 4.3 現象1の再現

環境は新規AVD `zerocon-verify`(Android 16 / SDK 36、x86_64)、シリアル `emulator-5558`。

既存の `emulator-5554`(Pixel_8)は使用していない。空きが375MBで、126MBのAPK更新には約394MB必要であり、`pm trim-caches` でも0MBしか解放されなかった。データを消さずに済ませるため、32GBのデータ領域を持つ別AVDを作成した。

追加したテスト `real_ime_cursor_into_converted_region_keeps_composition` は、`kyouhaiitenkidesune`(今日はいい天気ですね)を入力し、自動確定を無効・リアルタイムインラインを有効にしたうえで、画面の ◀ を1回ずつ押して各段階を検査する。結果は次のとおり失敗した。

```
Step 10 reverted the composition to raw kana: caret=2 text=きょうはいいてんきですね
```

キャレットがエンジンの変換済み領域(漢字側)へ入った位置で、IMEがcomposing text全体を生かなの読みへ置き換えた。報告された症状と一致する。

既存の `real_ime_cursor_prefix_commit_and_selection_count` が通るのは、読み `toukyouniiku` の末尾がかなのままで、`updateComposingDisplayOnCursorMove` の suffix 保持分岐が成立し続けるためである。変換済み末尾へキャレットを入れる位置を通っていない。

この再現では、次の2つを区別する必要がある。

|  sub-case | 内容 | 状態 |
| --- | --- | --- |
| (a) | プレビューが生かなへ戻る | 再現した(決定的) |
| (b) | composing region が終了し、生かなが確定される | コード経路は存在する(`onUpdateSelection` から `clearInput()` と `finishComposingText()`)。エミュレータでは未観測 |

(a) は確認済み、(b) は未観測である。

### 4.4 現象2の再現と実測

ニューラル経路(`requestCandidates`)に長い読みを段階的に与え、logcat の `ZeroConRepro` を記録した。読みは `キョウハイイテンキデスネ` の繰り返しである。

| 文字数 | 結果 |
| --- | --- |
| 36, 72, 108, 144, 192, 240, 300, 360, 420, 480 | 正常に復帰(510〜2337ms、候補 約346〜351件) |
| 540 | 上限エラーのみ(`org.json.JSONException: Value {"error":"request exceeds engine limits"} ... cannot be converted to JSONArray`)。1710ms |
| 600 | プロセスが停止(要求開始の305ms後) |

600文字での停止は `Fatal signal 4 (SIGILL), code 2 (ILL_ILLOPN)`、スレッド `DispatchWorker`、最上位フレームは `#04 ... $s24KanaKanjiConverterModule0C17requestCandidates...` である。

トゥームストーン `tombstone_05` のレジスタ値が決定的である。

| レジスタ | 値 | 十進 |
| --- | --- | --- |
| `rax` | `0x21f` | 543 |
| `r15` | `0x200` | 512 |
| `rcx` | `0x201` | 513 |
| `rbx` | `0x41f` | 1055 |
| `r13` | `0x420` | 1056 |
| `rdx` | `0x7fffffff` | 2147483647 |

543 が 512 を超えており、固定容量 `llama_batch_init(512, 0, 1)` のバッチ容量が律速である。`GGML_ASSERT` が成立せず停止した。このビルドでは `SIGABRT` ではなく `SIGILL`(不正命令トラップ)として現れるため、AndroidのOOMやANRではなくネイティブ側の強制停止と表現するのが正確である。

**重要な訂正**: 上限を決めるのは文字数ではなく**トークン数**である。480文字は512トークン未満で通過し、600文字で543トークンに達した。したがって文字数の閾値は文章の内容に依存し、この読みでは概ね500〜600文字である。固定の文字数を主張してはならない。

3.2で述べた「`n_kv_req > n_ctx` が死んだコードである」という指摘は、この観測と整合する。実際に効いている上限は `n_ctx`(1024)ではなくバッチ容量(512)である。

#### 別欠陥: JSONオブジェクトを配列として解析する

540文字で現れた `org.json.JSONException` は、現象2とは別の欠陥である。Rustブリッジ(`engine/rust/src/bridge.rs`)は `input.chars().count() > 512` で要求を拒否し、`{"error":"request exceeds engine limits"}` というJSONオブジェクトを返す。しかし `KanaKanjiConverter` は応答を `JSONArray(...)` で解析する。例外はIME側の `catch (error: Exception)` に捕捉されるため、クラッシュではなく変換の縮退になる。

この文字数ガード(512文字)は llama のバッチを保護しない。480文字は通過したが600文字は543トークンに達した。両者は無関係な上限であり、それぞれ対処が必要である。

### 4.5 計測時点のテスト状況

停止までに10件が通過した。`editor_setup`、`partial_commit_keeps_tail`、`real_input_connection_rewind`、`native_assets_and_model_load`、`shared_windows_conversion_and_input_snapshots`、`shared_cursor_prefix_with_and_without_ai`、`editor_context_change_without_cursor_move`、`native_dictionary_morphology`、`native_keyboard_typo_dictionary`、`native_long_compositions_and_unknown_characters`。

既存の長文テスト `native_long_compositions_and_unknown_characters` は195msで通っている。これは `ZenzNative.lookupDict`(LOUDS辞書経路)だけを使い、ニューラル経路を通らないためである。このカバレッジの穴を明記する。

## 5. 改善案

いずれも未実施の提案である。パッチは示さず、対象、方針、効果、リスク、優先度だけを記す。

### 5.1 現象1

**(a) 画面上の矢印も所有権を登録する**

- **対象ファイル**: Service.kt(`managedCursorKeys` の宣言 `:1489`、`onKeyDown` `:2422-2430`、`onKeyUp` `:2432-2435`、`onUpdateSelection` `:1643`)、KeyboardView.kt と Flick.kt の各呼び出し。
- **変更方針**: `managedCursorKeys: MutableSet<Int>` を所有権の根拠から外し、自己発行のエディタ書き込みを表す世代カウンタと、最後に書いた絶対キャレット位置・合成範囲開始位置を持つ `PendingWrite` に置き換える。`setManagedComposingText` と `ComposingEditorState.write` の両方が同じヘルパーを通り、書き込みごとに世代を進める。`onUpdateSelection` は「未消費の自己書き込みがあり、キャレットが一致する」場合だけ自己発行とみなす。ハードウェアキーの集合は長押し中の再入防止フラグとしてのみ残す。
- **期待効果**: 画面キーとハードウェアキーの区別がなくなり、入力経路によらず同じ所有判定になる。押しっぱなしでも所有権が切れない。
- **リスク**: 世代の消費漏れで本物の外部移動を自己発行と誤認すると、確定すべき場面で確定しなくなる。`clearInput`、`onStartInputView`、入力先切替で世代と未消費書き込みを破棄する必要がある。短期の有効期限も併用する。
- **優先度**: 高

**(b) カーソル移動時に合成文字列全体を生かなへ戻さない**

- **対象ファイル**: Service.kt:2437-2454、特に `:2447-2451`。
- **変更方針**: `currentPreview.endsWith(suffix)` が成り立たない場合でも `previewToSet = composingText.convertTarget` にしない。変換済みプレビューがあるなら、その文字列を保持したままキャレットだけを移す。キャレットが変換後の語の内部に入る場合は、カーソル前とカーソル後を別々に変換し、影響を受ける文節だけを読みに戻す。不変条件は「変換済みプレビューが存在するとき、合成文字列全体を読みへ戻さない」。
- **期待効果**: 手順7の破壊的リセットが万一走っても、composing region には変換後の漢字または文節単位の読みしか残らず、全文生かなの確定を避けられる。
- **リスク**: 変換後の表記と読みの境界対応が必要になる。カーソル前後の分割変換はリクエスト数が増えるため、`requestGate` とデバウンスで抑制する。文節境界の推定を誤ると候補の一貫性が落ちる。
- **優先度**: 高
- **根拠**: 4.3 の実測: `kyouhaiitenkidesune`(今日はいい天気ですね)でキャレットを2まで戻した時点で本文が `きょうはいいてんきですね` に戻った。修正の検証は、この「漢字に変換済みの領域へキャレットを入れる」条件を再現するテストで行う必要がある。既存の `real_ime_cursor_prefix_commit_and_selection_count` は末尾がかなのため、この条件を通らない。

**(c) 未確定入力中の `onUpdateSelection` を非破壊にする**

- **対象ファイル**: Service.kt:1622-1655。
- **変更方針**: `composingText` が空でない間は、所有権が確認できない通知でも直ちに `clearInput()` と `finishComposingText()` を呼ばない。新しいキャレットが合成範囲内にあり、プレビュー長と一致するなら、内部の読みカーソルを再同期する。再同期の対応付けには、変換候補が持つ消費読み長を使う。Swift側は `correspondingCount` として候補ごとに返し(`AndroidBridge.swift:146-159`)、Kotlin側では `Candidate.engineConsumedReading`(`ZenzNative.kt:18`)として受け取る。外部移動として扱うのは「合成範囲の外にキャレットがあり、かつ未消費の自己書き込みがない」場合に限る。
- **期待効果**: 症状の直接生成点を除去する。入力先アプリが範囲を省略しても、未確定入力が勝手に確定しない。
- **リスク**: 本物の外部移動(別段落のタップ、アプリによるテキスト置換)で確定が遅れると、古い合成が残る。範囲外かつ自己書き込みなしという条件を厳密にし、テキスト長の一致も確認する。再同期の位置が曖昧な場合は確定せず保留する方が安全である。
- **優先度**: 高

**(d) 16件の待ち行列を所有権の仕組みから外す**

- **対象ファイル**: EditorState.kt(`:23`、`:31-36`、`:42-47`、`:49-57`)、KeyboardView.kt の `RepeatingIconButton`(`:827-869`)と呼び出し側の50ms設定。
- **変更方針**: 所有判定を「最後に書いた絶対キャレット位置 + 世代」に一本化する。`recordPreview` も所有記録を残す(現在は `currentSelection = null` にするだけ)。自動連打は、フレーム単位で最新の移動要求だけを採用し、1フレーム1回のエディタ書き込みに coalesce する。50msごとに毎回 `setComposingText` と `setSelection` を呼ばない。
- **期待効果**: 押しっぱなしでも所有権が失われない。InputConnection の往復が減り、候補更新の追従も安定する。約400msで履歴が押し出される崖がなくなる。
- **リスク**: 連打時のバイブレーション回数と候補更新の頻度が変わる。移動回数に依存する既存テストの期待値更新が必要。世代の無効化漏れは(c)の誤判定に直結する。
- **優先度**: 高(記録漏れの修正)。coalesce 自体は中。

**(e) `confirmAsync` で生かなを黙って確定しない**

- **対象ファイル**: Service.kt:2520-2595、特に `:2575-2577` と `:2582-2588`。
- **変更方針**: 候補が空の場合と例外時は `currentSurface` を `commitText` しない。合成を維持したまま、読みそのものを候補として提示するか、確定せずにエラー状態を表示する。`isConfirming` の解除は現行どおり `finally` で保証する。読みを候補にする場合は学習対象の識別子を偽装しない。
- **期待効果**: 「候補なし」「エンジン例外」が生かな確定として現れなくなる。
- **リスク**: Enter が無反応に見える場面が生じるため、視覚的なフィードバックが必要。読み候補の扱いを誤ると学習履歴を汚す。
- **優先度**: 高

**(f) 回帰テスト**

- **対象ファイル**: `android/app/src/test/java/com/zeroconvert/ime/ComposingEditorStateTest.kt`、`android/app/src/androidTest/java/com/zeroconvert/ime/PortInstrumentationRunner.kt`。
- **変更方針**: 次を追加する。
  - 20回連続の左移動後も最新の選択通知を所有し、古い通知が遅れて届いても破壊しない。
  - キャレット末尾への書き込み(`recordPreview` 経路)直後の通知を所有する。
  - 画面キー経路とハードウェアキー経路が同じ書き込みヘルパーを通ることを表明する。
  - 実IMEで ◀ を20回連打し、composing span が残り、本文が生かなへ戻らない。
  - 末尾へ右移動した直後に範囲省略の `updateSelection` を送っても確定しない。
  - 候補0件と例外時に `commitText` が呼ばれない(fake converter)。
  - 変換済みの漢字領域へキャレットを入れる(`kyouhaiitenkidesune` で ◀ を2回)と、合成が生かなへ戻らない。4.3 の再現条件をそのまま固定する。
- **期待効果**: 症状の再発を経路単位で検出できる。
- **リスク**: 実IMEテストは端末とキーボード表示に依存するため、固定serialで実行する。
- **優先度**: 高

### 5.2 現象2

**(a) `engine limits` エラーを例外経路から外す**

- **対象ファイル**: `android/app/src/main/java/com/zeroconvert/ime/KanaKanjiConverter.kt`(応答解析箇所)、`engine/rust/src/bridge.rs`(上限判定)。
- **変更方針**: Rustブリッジは上限超過時に `{"error":"request exceeds engine limits"}` というオブジェクトを返すが、Kotlin側は `JSONArray(...)` で解析するため `JSONException` になる。応答がオブジェクトか配列かを判別し、オブジェクトの場合は明示的な「変換不能」結果として扱う。例外に頼らず、辞書経路の候補へ縮退させる。
- **期待効果**: 4.4で実測した540文字の事象が、例外ではなく通常の縮退になる。ログも1行で読める。
- **リスク**: 応答形式の分岐が増える。RustとKotlinで上限値(現在512文字)の定義が二重管理にならないよう、定数の出所をそろえる。
- **優先度**: 高
- **根拠**: 4.4 の 540文字の実測。この文字数上限は llama のバッチ容量とは無関係であり、600文字で起きた停止の防止にはならない。

**(b) ニューラル経路の前に実トークン数・長さのガードを置く**

- **対象ファイル**: `windows/server-swift/Sources/azookey-server/azookey_server.swift`(`makeConvertRequestOptions` `:1464-1503`、`get_composed_text` 系の `:3691`、`:3965`、`:4072`)。WindowsとAndroidが同じソースを共有するため、ここに置けば両方に効く。
- **変更方針**: リクエストごとに、組み上がるプロンプトの実トークン数(プロンプトの骨格 + 左文脈 + 読み + 候補見積り)を評価し、`n_batch` を余裕分だけ下回る上限を超える場合は `zenzaiMode = .off` にするか、文節単位の経路へ切り替える。文字数ではなくトークン数を基準にする。実測では480文字が512トークン未満で通過し、600文字が543トークンに達したため、文字数のガードではバッチを境界付けできない。辞書のみの候補は残す。判定は `ConvertRequestOptions` を組む直前で行い、`requestRichCandidates` と `inferenceLimit` も長文では下げる。
- **期待効果**: 長文でも候補品質は落ちるが、プロセスは落ちない。両プラットフォームで同じ挙動になる。
- **リスク**: 閾値が小さすぎると通常長の入力でZenzaiが無効になる。文節分割は候補の一貫性を変える。閾値は5.2(g)の計測で決める。
- **優先度**: 高
- **根拠**: 4.4 の実測(543トークン / 512容量、`tombstone_05` の `rax=0x21f`, `r15=0x200`)。

**(c) バッチ容量をトークン数に追従させる**

- **対象ファイル**: `windows/scripts/converter_patch.py:26-29` と、そのパッチ対象である ZenzContext.swift:149-163。
- **変更方針**: `llama_batch_init` の容量を `tokens.count` に合わせるか、`llama_n_batch(context)` 単位でプリフィルを分割して複数回 `llama_decode` する。`n_ctx` を1024へ上げたのに `n_batch` を512のままにした不整合を解消する。分割する場合は `logits_start_index`、`prefixCacheCount`、`prevInputBySeq` の意味を保つ。
- **期待効果**: 512超のプロンプトでも配列外書き込みが起きない。
- **リスク**: パッチのアンカーは固定リビジョンとの契約であり、`--check-converter` での検証が必要。分割デコードはロジット位置とKVキャッシュの前提を変えるため、Windows側の回帰も必要。`n_batch` を1024へ上げるだけなら配列は小さいが、ubatchの計算量とメモリは増える。
- **優先度**: 高
- **根拠**: 上限は `n_ctx`(1024)ではなくバッチ容量(512)であることが実測で確定した(`rax=0x21f` 対 `r15=0x200`)。したがって `n_batch` の引き上げ、または分割プリフィルのどちらでも容量側を直せば 543 トークンの入力は停止しない。

**(d) 死んでいる `n_kv_req` 検査を実効的なガードに置き換える**

- **対象ファイル**: ZenzContext.swift:152-155。
- **変更方針**: `n_kv_req` の計算をやめ、`tokens.count <= Int(llama_n_ctx(context))` と、分割しない場合は `tokens.count <= Int(llama_n_batch(context))` を検査する。超えた場合はデコードせずに `nil` を返す。`ZenzEvaluator.swift:68-70` は `nil` を `.error` として扱うため、呼び出し側は候補を減らして継続できる。
- **期待効果**: 中断ではなく変換失敗として縮退する。
- **リスク**: 失敗が増えると候補が乏しくなる。ログに理由を残し、5.2(b)のフォールバックと組み合わせる。
- **優先度**: 高
- **根拠**: 実測では 543 トークンで停止しており、`n_ctx` 超過より先にバッチ容量で落ちる。`n_kv_req` が常に512を返す計算になっているため、`n_ctx` の検査だけでは今回の停止を防げない。

**(e) `ggml_set_abort_callback` では中断を防げないと明記する**

- **対象ファイル**: ggml.c:247-273。
- **変更方針**: コールバックはログ用途に限定し、安全装置として依存しない。`abort()` は無条件に実行されるため、防止は `llama_decode` 前の検査で行う。設計メモとコメントにこの制約を残す。
- **期待効果**: 誤った防御策の追加を防ぐ。
- **リスク**: なし(文書化のみ)。
- **優先度**: 中

**(f) IME層の長さ上限(多層防御)**

- **対象ファイル**: `AndroidBridge.swift:81-82`、および Kotlin 側のリクエスト生成。
- **変更方針**: AI変換に渡す合成文字列のコードポイント数に上限を設け、超える場合は辞書のみの変換を要求する。`elements.count <= 4096` は転送量の上限としては残すが、トークン数の上限ではないと明記する。
- **期待効果**: サーバ側のポリシーが退行しても、ネイティブの配列外書き込みに到達しない。
- **リスク**: 利用者に見える hard limit になる。確定済みテキストを切り捨てない。上限値は5.2(g)に合わせる。
- **優先度**: 中

**(g) 計測計画**

- **対象ファイル**: ZenzContext.swift:141(既に `tokens count:` を debug 出力している)、`ZenzEvaluator.swift:66-68`。
- **変更方針**: 読みの長さ(50、100、200、300、400、500文字)と左文脈の有無を変え、`tokens.count`、`promptTokens.count`、`candidateTokens.count`、`n_batch`、`n_ctx`、モデル識別子を記録する。キャレット前変換と全文変換の両方を測る。得られた回帰から安全上限を決める。
- **期待効果**: 4.4 の実測を他の文章種別や左文脈の有無へ広げ、5.2(b)(c)(f)の閾値を根拠付きで決められる。
- **リスク**: モデルやトークナイザが変わると再計測が必要。
- **優先度**: 高

### 5.3 検証基盤

**(a) Swiftランタイムを単一のSDKアーカイブから揃える、またはビルド時に検査する**

- **対象ファイル**: `scripts/prepare-android-swift-runtime.ps1:9-16`、`scripts/extract-android-swift-runtime.py:13`、`scripts/build-android-shared-engine.ps1:43-48`、`:115`。
- **変更方針**: 必要なランタイム(`libswiftCore.so`、`libswift_Concurrency.so`、`libBlocksRuntime.so` など)も同じ検証済みアーカイブから展開する。難しければ、ビルド時に同梱する各 `.so` の未定義シンボルと、他の `.so` の定義シンボルを突き合わせ、未解決があれば失敗させる。`llvm-readelf --dyn-syms` などで機械的に判定する。
- **期待効果**: x86_64エミュレータでエンジンが起動でき、現象1と現象2の実行検証が可能になる。混成ランタイムの再発を防ぐ。
- **リスク**: 同梱ファイルが増え、指紋とライセンスの管理対象が広がる。アーカイブのchecksum固定(`prepare-android-swift-runtime.ps1:10-15`)は維持する。
- **優先度**: 高

**(b) ランタイムファイルをソース指紋に含める**

- **対象ファイル**: `scripts/prepare-shared-engine.mjs:102-145`、`engine/shared-engine.lock.json`、`scripts/build-android-shared-engine.ps1:112-115`、`:138-143`。
- **変更方針**: ステージしたランタイム `.so` のsha256を `sources.json` と `lock.json` に含める。`libraries.json` は既に `.so` を列挙しているが、ランタイムのコピー元とビルド識別子も記録する。
- **期待効果**: ランタイムだけが変わった場合に `runtime-$revision` のキャッシュや成果物の同一性が崩れず、混成を検出できる。
- **リスク**: lockの更新頻度が上がる。
- **優先度**: 中

**(c) クラッシュ記録にビルド識別子を含める**

- **対象ファイル**: クラッシュトレース出力(azookey_server の crashTrace 経路)、Android側の起動ログ。
- **変更方針**: 起動時に `applicationId`、`versionCode`、`versionName`、組み込み可能ならGitコミット、ロードした各 `.so` のBuildIdとsha256を記録し、クラッシュトレースと同じ場所へ出す。個人情報や入力内容は含めない。
- **期待効果**: トゥームストーンとビルドを対応付けられ、SIGTRAPクラスが現行で解消したか判断できる。
- **リスク**: 配布ログに含める情報の管理が必要。
- **優先度**: 中

**(d) 修正後はx86_64でinstrumentation、次にarm64実機**

- **対象ファイル**: テスト実行手順(`docs/android-development.md`)。
- **変更方針**: `assembleDebugAndroidTest` でテストAPKを作り、対象を `-s` で固定して `am instrument -w -r` を実行し、tests/failuresと個別結果を確認する。x86_64で全件通した後、arm64実機で実IMEの矢印と長文を確認する。
- **期待効果**: エミュレータで論理を、実機で入力経路を確認できる。
- **リスク**: x86エミュレータの結果をarm64実機の性能や挙動の保証に使わない。
- **優先度**: 高

## 6. 推奨する着手順

4.2 の検証基盤修正は完了済みであり、以降の項目(現象1・現象2の修正、5.1(f)と5.3(d)の確認)は未実施である。5.2(a) の例外経路の除去と 5.2(b) のトークン数ガードは、どちらも実測済みの事象に対応するため、最初に着手する価値がある。

1. **検証基盤(a)(b)**。x86_64でエンジンが起動しない限り、現象1も現象2も実行検証できず、修正の効果を測れない。dlopenの失敗は再現性のある具体的な原因(txtに記録済み)なので、最初に潰す価値が最も高い。
2. **現象2(d)(c)**。`abort()` は復旧不能で、利用者の入力がそのまま失われる。ガードとバッチ容量の修正は局所的で、計測前でも安全側に倒せる。続けて5.2(b)(f)のプラットフォーム共通ポリシーを入れる。
3. **現象1(b)(c)**。生かな確定の直接生成点を先に塞ぐ。プレビュー復帰の廃止と `onUpdateSelection` の非破壊化は、症状の再現条件に直結する。
4. **現象1(a)(d)(e)**。所有権の仕組みを世代ベースへ置き換え、記録漏れと連打の崖をなくす。`confirmAsync` の暗黙確定も同時に塞ぐ。
5. **5.1(f)と5.3(d)**。JVMテスト、instrumentation、arm64実機の順で確認する。

根拠: 検証基盤が最初なのは、これがないと「直った」と「測れていない」を区別できないため。現象2を現象1より先にするのは、クラッシュが不可逆で影響が大きいため。現象1は(b)(c)だけで症状の主経路を止められるため、(a)(d)の機構変更より先に置く。

## 7. 未確認事項

- 報告端末が Snapdragon 8 Elite 搭載機であることは、今回読んだログからは確認していない。
- 現象1は 4.3 でエミュレータ再現済みだが、入力先アプリごとの `onUpdateSelection` の範囲の返し方は未収集である。
- 「8回で上限、約400msで押し出し」はコード上の算術であり、実測ではない。`currentSelection` の照合があるため、押し出し単独での所有権喪失は確認していない。
- 4.4 では読み480文字が512トークン未満で通過し、600文字が543トークンで停止した。プロンプト全長と候補長の内訳は未計測である。
- 3.2の閾値は4.4の実測(480文字で通過、600文字で543トークン)に基づく。
- 現象2の発生時にZenzaiが有効だったか、どのモデルをロードしていたか、実行時の `n_ctx` と `n_batch` がいくつだったかは未確認である。
- `n_seq_max = 2` と `kv_unified = true` の追加がKV容量の意味に与える影響は評価していない。
- SIGTRAPクラスが現行ビルドで解消しているかは未確認である。トゥームストーンにビルド識別子がない。
- arm64実機での現象1と現象2の再現、長文の閾値付近の挙動、および修正効果の確認は未確認である。
- sub-case (b)(composing region が終了して生かなが確定される経路)が実機で発生するかは未確認である。4.3 では (a) のみ観測した。
- 漢字混じりやラテン文字など、他の文章種別でのトークン数対文字数の比は未計測である。
- ビルドスクリプト変更後も arm64-v8a の挙動が変わらないことは未確認である(arm64のランタイムは意図的に未変更で、jniLibs のハッシュも不変)。
- アプリ本体のソース修正は行っていない。検証基盤(ビルドスクリプト3件)は修正・ビルド済みで、x86_64エミュレータでの再現まで確認した。arm64実機への適用と実機確認は行っていない。
