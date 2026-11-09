# Android 翻訳入力（試験機能）

2026-11-05 追加。Windows の翻訳入力（`docs/investigations/windows-translation-input.md`）をAndroidのIMEへ移植した試験機能。WindowsのTSF・別プロセス常駐サーバー構成は持ち込まず、Androidの`InputConnection`と別JNIランタイムで実装する。

## 操作

1. 設定画面の「翻訳入力（試験）」で「翻訳機能を有効にする」をONにする。
2. 初回は「翻訳モデルをダウンロード」の確認ダイアログを表示する。同意するとHugging Faceから取得する。
3. キーボードの翻訳アイコンを押すと翻訳モードに入る。
4. 日本語を通常どおり入力する。入力欄には日本語の未確定文が表示される。
5. 最後の打鍵から400ms後に、かな漢字変換した文を翻訳し、入力欄の未確定文字列を訳文へ置き換える。
6. **Enter**で訳文を確定してモード終了。翻訳待ち・失敗時は日本語を誤確定せず、キーを消費する。
7. **× / 翻訳アイコン再タップ**で翻訳を取り消し、日本語の未確定文へ戻ってモード終了。
8. 入力先・アプリのフォーカス変更、キーボード終了、画面回転などではモードを終了し、古い結果を破棄する。
9. 文字選択中は選択バーの「翻訳」から選択翻訳を実行し、結果バーの「挿入」で選択範囲を置換、「コピー」で訳文をコピーする。選択翻訳は入力欄を自動では書き換えない。

翻訳モード中の英字キーボード入力にも対応する。英語を入力した場合は日本語方向へ翻訳する（設定が「自動」の場合）。

## モデル

- 配布元: `pfnet/plamo-3-translate-0.8b-GGUF`（Hugging Face）
- 固定リビジョン: `03891e907db8a43c97d3a7c75add125cc6d1f96a`
- ファイル: `plamo-3-translate-0.8b-Q4_K_M.gguf`
- サイズ: 573,116,288 bytes（約547MiB）
- SHA-256: `3dbb09295bfd3350231ba0dba92bc72cb14111ef1b034e37dad39d581bd86b9c`
- ライセンス: PLaMo Community License（`LICENSE/ja`）

APKには同梱しない。設定画面で同意した場合だけ、HTTPSで`files/translation/`へダウンロードする。途中ファイル（`.part`）を残してRange再開できる。完了時にサイズとSHA-256を検証し、検証済みマーカーを保存する。Hugging Faceのリビジョンを固定しているため、上流の更新で無断に内容が変わることはない。ダウンロードはMutexで排他し、成功時に以前のエラー表示を消す。

翻訳推論は端末内のllama.cppで行う。入力文・選択文・訳文を外部サーバーへ送信しない。ネットワークを使うのはモデルのダウンロードだけである。翻訳モデルと途中ファイルはAuto Backup・端末間転送の対象外にする（`res/xml/backup_rules.xml` / `res/xml/data_extraction_rules.xml`）。

## 推論とメモリ

- Androidの同梱llama.cpp（コミット`8115b4c`）は`plamo3`アーキテクチャとPLaMo3トークナイザに対応済み。
- 変換モデル・Corrector・先読みとは別の`Runtime`を`assist_jni.cpp`に追加した。翻訳の世代（epoch）も分離し、通常入力の取消が翻訳を止めたり、その逆が起きたりしないようにする。
- 翻訳モデルは`LLAMA_LOAD_MODE_MMAP`で読み込む。`n_ctx=4096`、4スレッド、最大512トークン、最大15秒。出力は制御文字・`<|plamo:`・512文字超を拒否し、`limit`（打ち切り）は成功扱いにしない。
- モデルは翻訳モード開始時に遅延ロードする。設定「翻訳モデルを常時読み込む」がOFF（既定）なら、モード終了時に解放する。ONならプロセス終了まで保持する。RAM/VRAMへの影響を利用者に示す。
- 翻訳結果はかな漢字変換の候補・学習へ追加しない。確定した英文を日本語の変換履歴として学習しない。

## 入力経路

翻訳モード中は通常の候補生成・自動確定・文節変換・AIアシストを停止する。

1. 打鍵は既存の`ComposingText`（読み・未確定ローマ字・cursor）へ入る。
2. `triggerDebouncedConversion`が翻訳モードを検出し、通常変換の代わりに翻訳ジョブを開始する。
3. 翻訳ジョブは400ms待機後、`KanaKanjiConverter.requestCandidates`で日本語表記を得てからPFML3プロンプトを組み立てる。
4. 結果到着時に世代・入力欄・入力文字列・選択状態を照合し、一致した場合だけ`setComposingText`で訳文へ置き換える。
5. Enterは訳文を`commitText`し、翻訳状態を学習なしで破棄する。
6. 取消は日本語の未確定文を`setComposingText`で戻す。

翻訳モード中はカーソル左右移動・範囲変換・再変換を無効化する（試験範囲）。削除・追加入力・句読点・数字は読みへ反映して再翻訳する。翻訳モード中の数字はQWERTY・12キーともliteral注入（`appendTranslationLiteral`）で読みへ反映し、変換候補を経由しない。かな・カタカナ・漢字を1文字も含まない読み（数字・記号のみ）は翻訳要求を出さず、翻訳バーをIDLEのままにする（Windowsのkind=3 eligibilityに揃えた。`translationSourceEligible` / `hasJapaneseKana`）。

## 設定

`ime_settings` SharedPreferences:

| キー | 型 | 既定 | 内容 |
| --- | --- | --- | --- |
| `translation_enabled` | boolean | false | 翻訳機能の有効化。OFFでは翻訳アイコンを出さない |
| `translation_target` | string | `auto` | `auto` / `en` / `ja`。`auto`は日本語を含めば英語、それ以外は日本語 |
| `translation_keep_loaded` | boolean | false | モード終了後もモデルを保持する |

初回ONのダウンロード確認と進捗・削除・テスト翻訳は設定画面（`MainActivity.kt`）が行う。IME側は設定変更をSharedPreferencesリスナーで読み直す。

## 実装箇所

| ファイル | 役割 |
| --- | --- |
| `android/app/src/main/java/com/zeroconvert/ime/translation/TranslationText.kt` | PFML3プロンプト、原文・訳文検証、言語判定 |
| 同 `TranslationSession.kt` | 要求世代の無効化 |
| 同 `TranslationModelManager.kt` | ダウンロード、Range再開、SHA-256検証、削除、空き容量 |
| 同 `TranslationEngine.kt` | 翻訳モデルの遅延ロード・解放 |
| `android/app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt` | 翻訳モード、入力翻訳、選択翻訳、ライフサイクル |
| `android/app/src/main/java/com/zeroconvert/ime/ComposeKeyboardView.kt` | 翻訳アイコン、翻訳バー、選択翻訳バー |
| `android/app/src/main/java/com/zeroconvert/ime/MainActivity.kt` | 有効化、ダウンロード確認・進捗、削除、翻訳テスト |
| `android/app/src/main/java/com/zeroconvert/ime/ZenzNative.kt` | 翻訳JNI呼び出し |
| `android/app/src/main/cpp/assist_jni.cpp` | 独立した翻訳Runtime、mmapロード、推論、epoch取消 |
| `android/app/src/main/AndroidManifest.xml` | `INTERNET`権限 |

## 検証状況

2026-11-05〜06:

- `.\gradlew.bat :app:testDebugUnitTest`: 成功（既存テスト＋`TranslationTextTest` 6件、failures 0）。
- `.\gradlew.bat :app:lintDebug`: 成功（0 errors、28 warnings。翻訳固有のエラーなし）。
- `.\gradlew.bat :app:assembleDebug` / `:rebuilt:assembleDebug`: 成功（arm64-v8a / x86_64）。
- iQOO Neo11 V2520A（Android 16、SDK 36、arm64-v8a）へdebug APKを `adb install -r` で更新した。設定画面でPLaMo 3 Translate 0.8Bのダウンロード（573,116,288 bytes、SHA-256 `3dbb0929…b86b9c` が一致）と、「会議は明日です。」→「The meeting is tomorrow.」の翻訳テストを確認した。
- 翻訳モード（キーボードの翻訳アイコン、休止後の置換、Enter確定、取消）と選択翻訳は利用者が実機で確認済み。
- リリース用 `rebuilt` APKは versionCode 14 / versionName 2.0.12-dev、arm64-v8a。`libzenz_jni.so` に `nativeInitTranslation` / `nativeTranslate` / `nativeTranslationEpoch` / `nativeAbortTranslation` / `nativeCloseTranslation` のシンボルを確認した。
- 端末切断のため、最終リリースAPKの再インストールと実IME再確認は未実施。
- 未確認: 2Bモデル、長文・高速連続打鍵、複数アプリ、複数DPI、翻訳品質の定量評価、端末別の遅延・発熱・メモリ。x86_64エミュレータの結果をarm64実機の性能保証にしない。

### 配布前の再確認手順（最終APK）

1. 最終リリースAPKを `adb -s <serial> install -r` で更新し、設定・学習・IME有効化状態が保持されることを確認する。
2. 別の入力アプリで翻訳アイコンを押し、「会議は明日です。」を入力する。
3. 400ms以上待ち、入力欄が英訳へ置き換わることを確認する。Enterで確定し、モードが終了することを確認する。
4. 翻訳待ち中のEnterが日本語を確定しないこと、×で日本語の未確定文へ戻ることを確認する。
5. 選択翻訳→挿入/コピー、入力先切替・キーボード終了で結果が混ざらないことを確認する。
6. 常時読み込みOFF/ONで、モード終了後のプロセスメモリと再入時の待ち時間を比較する。
7. 端末/OS/ABI、モデルSHA-256、設定、待ち時間、発熱、メモリを記録する。

## 制約・未対応

- 翻訳モード中のカーソル移動・文節範囲変換・再変換は無効。文単位の入力確定を優先した試験実装。
- 翻訳は文単位の完了更新で、トークン単位のストリーミング表示ではない。
- 選択翻訳の結果は入力欄外のバー表示で、フローティングウィンドウではない。
- 翻訳モデルは0.8B Q4_K_Mのみ。2Bや他の量子化の設定UIはない。
- ダウンロードはアプリ内のHTTPS取得のみ。外部ブラウザ・ストア配信・Wi-Fi限定判定はない。
- パスワード等のsensitive入力欄では翻訳を開始しない。
- 翻訳結果をユーザー辞書・かな漢字変換の学習へ追加しない。
- モデルのライセンス条件（商用利用の登録・収益条件）は利用者・配布者が確認する。アプリはライセンス全文へのリンクを表示するのみ。
