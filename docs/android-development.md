# Android版の修正・テスト・端末反映・配布

Windows共通エンジン版は `:rebuilt`。構造・ビルド入口・移行・未確認点は
[Android新構成](android-shared-engine.md) を参照。2026-11-08に `:app` モジュールを廃止し、
`android/app/` は `:rebuilt` がコンパイルする共有ソースの置き場になった。
以下の手順と過去の実機結果は、`:app` 廃止後も `:rebuilt` にそのまま適用する。

共通エンジン版の導入用APKはCPU別に出力する。ARM64実機は
`android/rebuilt/build/outputs/apk/debug/rebuilt-arm64-v8a-debug.apk`、x86_64は
`rebuilt-x86_64-debug.apk`。現在の出力は同じフォルダの `output-metadata.json` で確認する。
`Build-Android.bat -UniversalApk` では両CPUを含む `rebuilt-universal-debug.apk` も作成できる。
旧 `rebuilt-debug.apk` は新しいビルドで更新されないので導入しない。
`Build-Android.bat` は `:rebuilt` だけをビルドする。`android/app/build/` は生成されない。
2026-10-26、vivoへ旧1.0.0のAPKを再導入したため英字Enter修正が反映されなかった。
版・ハッシュで修正版を識別する。詳細は [英字Enterの記録](investigations/android-english-enter.md)。

2026-10-23、現在のAndroidソースとGradle設定を確認して整備。エージェント向けの必須ルールは [android/AGENTS.md](../android/AGENTS.md)。Windowsのbatと混同しないため、Androidの作業手順はこの文書を正本とする。

## 作業の流れ

```text
android/のソースを修正
  → JVM回帰テスト・lint・APKビルド
  → 対象端末へAPKを更新インストール
  → 端末の設定画面と別アプリで実IME確認
  → 配布用署名済みAPKを固定・そのAPKを再確認
  → 同じAPKを配布
```

Git保存はこの流れと別の操作。コミットしただけ、APKを作っただけでは端末のIMEは更新されない。`Build-Trial.bat` / `Apply-Trial.bat` / `Build-Installer.bat` はWindows用で、Androidには使わない。

今回追加するのはルールと手順であり、自動ビルド・端末選択・インストール・署名・配布を行う新しいbatやCIは作成していない。

## 現在の構成と未整備の点

| 項目 | 現在の設定・状況 |
|---|---|
| applicationId / namespace | `com.zeroconvert.ime` |
| 版 | `android/gradle.properties` の `zerocon.versionCode` / `zerocon.versionName` |
| SDK | `android/gradle.properties` の `zerocon.compileSdk` / `zerocon.targetSdk` / `zerocon.minSdk` |
| ビルド | Gradle wrapper 8.7、Android Gradle Plugin 8.3.2、Kotlin 1.9.23、JDK 17 |
| native | `zerocon.ndkVersion`、CMake 3.22.1、C++17、`engine/native` のJNI |
| ABI | arm64-v8a、x86_64 |
| JVMテスト | 入力状態、フリック、補正・定型句先読み、自動確定のテストあり |
| 端末内テスト | `PortInstrumentationRunner`。debug専用入力欄で実InputConnection・native辞書/モデル・IME操作を検証 |
| release署名 | GradleにsigningConfigs/release署名の設定なし |
| CI | ルートのSource checksはAndroidビルド・テスト・実IME操作を実行しない。`shared-engine-inputs` ジョブだけが共有エンジンのステージング・pin・ロックを検査する |

これは現状の記録であり、配布先の要件を満たす保証ではない。SDK・版・署名等を変更したらこの表も更新する。Google Play公開は別途、その時点のtarget SDK・サイズ・審査等の要件を確認する。

## 修正するファイル

`android/app/src/main/java/com/zeroconvert/ime/` をKotlinソースの起点とする。

| 変更 | 主な場所 |
|---|---|
| 打鍵・フリック結果の入力、確定・削除・カーソル | `ZeroConvertInputMethodService.kt`、`ComposingEditorState.kt`、`EditorContext.kt`、`azookey/input/ComposingText.kt` |
| かな漢字変換・学習候補・ユーザー辞書 | `KanaKanjiConverter.kt` |
| 補正・先読み・補正学習 | `assist/TypoCorrectionEngine.kt`、`PredictiveAssistEngine.kt`、`AiAssistManager.kt`、`TypoLearning.kt` 等 |
| キーボード・候補バナー | `ComposeKeyboardView.kt`、`KeyboardCandidateArea.kt`、`FlickKeyboardView.kt` |
| 設定画面・設定キー・既定値 | `MainActivity.kt`、サービスの設定読込 |
| モデル管理・JNI API | `model/ModelManager.kt`、`ZenzNative.kt` |
| 推論・辞書・CMake | `android/app/src/main/cpp/` の自前ファイル |
| 権限・IME登録・共有 | `AndroidManifest.xml`、`res/xml/` |
| 回帰テスト | `android/app/src/test/java/com/zeroconvert/ime/` |

Windowsから移植するときは、読み・表記・原打鍵・先読み追加部分、補正適用/取消/確定を区別する。WindowsのRPCやTSFの仕組みをAndroidへそのまま持ち込まず、InputConnection・サービスのライフサイクルへ対応させる。フリックや自動確定巻戻しなどAndroidの既存機能も保持する。

## ビルド環境とモデル

現行の移植範囲と検証状況は [完了監査](../reports/port-history/android-port-completion-audit.md) を参照。[第5段階の移植記録](../reports/port-history/android-windows-port-stage5.md) にある専用の範囲変換UIは取り下げ済み。Windows現行のロジックを既存の入力操作へ接続し、独自の文節操作モードは追加しない。既存のカーソル前部分確定と末尾の入力状態を保持する修正は維持する。

最新の補正規則・登録語保護・APKは [第4段階の移植記録](../reports/port-history/android-windows-port-stage4.md) を参照。語彙生成スクリプトは `WindowsTypoData.kt` も生成し、`--check` で語彙TSVと合わせて照合する。このKotlinファイルは手編集せず、Windowsの生成元を確認して再生成する。ユーザー辞書の更新は補正側の参照マップと要求世代にも反映される。

通常辞書側の補正・探索・ユーザー辞書・学習モードは [第8段階](../reports/port-history/android-windows-port-stage8.md) を参照。`node scripts/generate-android-keyboard-typos.mjs` がWindowsの選択データと生成関数からnative用の104エントリ、端末検査用データ、APK同梱NOTICEを生成する。`--check` で同期を検査し、生成された `keyboard_typo_dictionary.inc` を手編集しない。`experimental_typo_correction` は既定falseで、AIアシストと独立したローマ字入力用設定。通常辞書は語長20・全文最大512まで探索する。登録語は要求ごとにnativeへ渡し、キャッシュキーにも含める。

通常確定の学習は共有エンジン（Swift）の1系統に統合した。選択表記は初回に `LearningMemory/selection-overrides.json` へ移行し、Android独自の `learning_memory_v2_json` と形態素頻度バイナリ `files/conversion-learning-v1.bin` は廃止した。第9段階・第10段階の記録は当時の実装であり現在の構成ではない。学習の削除は2経路ある。設定画面は `KanaKanjiConverter.clearAllLearning` で変換学習と補正学習をスコープ別に消し、候補バーの長押しは `WindowsEngine.forgetCandidate` で候補IDをエンジンへ渡す。どちらも削除の判断と辞書操作はSwift/Rust側にあり、Kotlinは候補IDと確認UIだけを持つ。変換学習のリセットは世代カウンタで削除前の未処理通知を捨てる。過去の検証内容は [第9段階](../reports/port-history/android-windows-port-stage9.md) と [第10段階](../reports/port-history/android-windows-port-stage10.md) を参照。

最新の実装・APK・検証範囲は [第3段階の移植記録](../reports/port-history/android-windows-port-stage3.md) を参照。資産準備はWindows補正語彙から `assets/assist-vocabulary.tsv` も生成する。このTSVはGit対象であり、Windows語彙更新後は `node scripts/generate-android-assist-vocabulary.mjs` で再生成し、`--check` で一致を検査する。重みと逆引き辞書は引き続きGit対象外。

設定 `auto_typo_correction` は既定trueで、AIアシスト設定と併せて読み直す。自動補正は取消可能で、自動適用自体を学習しない。既存の学習回数は保持するが、自動適用には新しい確定成功回数を使う。選択文字列の再変換は辞書のみで、通常のモデル変換・補正・先読みとは別経路。

モデル補正・二段先読みの資産準備が追加された。初回や資産更新時はルートで `./scripts/prepare-android-assist-assets.ps1` を実行する。CorrectorはWindowsカタログのSHA-256で検証し、逆引き辞書はAndroid同梱のLOUDSソースから生成する。どちらもGit対象外のため新しいcloneには含まれない。GradleのpreBuildは不足や不整合を検出して停止する。用途別JNI・現在の資産・テスト・未確認点は [第2段階の移植記録](../reports/port-history/android-windows-port-stage2.md) を参照。

JDK 17、上表のSDK/NDK/CMake、Gradle wrapperを使う。SDKのPC固有パスは `android/local.properties` 等のローカル設定で管理する。既存の設定を上書きせず、SDKライセンスや端末認証を無断で通過しない。

llama.cppはルートのgitlinkで固定し、独自変更は `patches/llama.cpp/` と [setupスクリプト](../scripts/setup-dependencies.ps1) で管理する。依存ソースやビルド出力を直接書き換えて済ませない。

組込みモデルは `android/app/src/main/assets/zenz.gguf` と `assets/models/`。現在の選択肢・名前・期待サイズは [ModelManager.kt](../android/app/src/main/java/com/zeroconvert/ime/model/ModelManager.kt) を照合する。重みはGit対象外なので、新しいclone/worktreeだけではそろわない。モデルを含むAPKと、端末で実際に選択中の取込モデルも区別する。

絵文字パレットの `assets/emoji_catalog.tsv` は、固定したUnicodeデータ `data/emoji/emoji-test-15.1.txt`（Unicode License v3、CLDR順）と、変換辞書と同じ `assets/emoji_all_E15.1.txt` の日本語キーワードから `node scripts/generate-android-emoji-catalog.mjs` が生成する。1,921件を9カテゴリに分け、肌色5段階を持つ323件は長押しで展開する。`--check` で同期を検査し、生成TSVを手編集しない。Unicodeデータを更新するときは版を上げたファイルを追加し、生成スクリプトの `SOURCE` と `UNICODE_GROUP_ORDER` を合わせて確認する。パレットの解析・検索・最近使用は `EmojiCatalog.kt`、最近使用の保存は `ime_settings` の `emoji_recent`。

翻訳入力のPLaMo 3 Translate 0.8B（Q4_K_M、573,116,288 bytes、SHA-256固定）はAPKに同梱せず、設定画面で同意した場合だけHugging Faceから `files/translation/` へダウンロードする。取得・検証・削除は `translation/TranslationModelManager.kt`、遅延ロードは `translation/TranslationEngine.kt`、JNIは `assist_jni.cpp` のTranslation Runtimeが担当する。モデル・ライセンス・操作・未確認範囲は [Android翻訳入力](investigations/android-translation-input.md) を参照する。端末へ配布する場合は、ダウンロード済みモデルをAPKへ含めない。

モデルを変更したら用途・元ファイル・量子化・サイズ・SHA-256を記録し、選択情報と端末内展開済みモデルが更新されることを確認する。組込みモデルは `BuiltInModelSpec.sha256` とサイズで既存ファイル・展開した一時ファイルを検証してから置き換える。保存済み選択先が管理下の組込みモデルであれば起動時にも検証する。利用者が取り込んだ別パスのモデルを同名だけで上書きしない。

## テストとAPKビルド

以下のGradleコマンドは **android/内のPowerShell** で実行する。

```powershell
.\gradlew.bat :rebuilt:testDebugUnitTest
.\gradlew.bat :rebuilt:lintDebug
.\gradlew.bat :rebuilt:assembleDebug
```

基本はこの3段階。テストは `rebuilt/build/reports/tests/testDebugUnitTest/`、lintは `rebuilt/build/reports/`、debug APKは `rebuilt/build/outputs/apk/debug/` に出る。実際の出力と終了コードを確認する。過去のAPKやログがあるだけでは今回の成功としない。

JVMテストは除外リスト方式で、新しいテストは自動的に実行される。除外はメソッド単位で、旧Kotlin補正・先読み経路（共有エンジンが代替）とJVMからロードできないJNI呼び出しの2種類だけ。除外を足すときは理由を書く。

純粋なKotlinの入力・候補選択ロジックはJVMテストへ、InputConnection・IMEライフサイクル・JNI・モデル読込・実際のキーボード表示は端末確認へ割り当てる。JVMテスト設定の `isReturnDefaultValues=true` と、nativeロード失敗を捕捉する経路があるため、ホスト単体テストでnative機能を検証したつもりにならない。

### 共有エンジンを触ったときの追加確認

`:rebuilt` はWindowsのSwift/Rustソースとvendored converterをそのままコンパイルする。次のいずれかを変更したら、APKビルドの前にルートで共有エンジンの検査を通す。

- `windows/server-swift/Sources/`、`windows/crates/shared/src/conversion_policy.rs`、`engine/` 配下
- `windows/scripts/converter_patch.py`、`prepare-modern-llama.py` のconverter pin
- `windows/server-swift/Package.resolved`（Android側の `engine/Package.resolved` と一致させる）
- `android/app/src/main/cpp/` の自前nativeソース

```powershell
# ルートで実行
python scripts/run.py windows/scripts/test_converter_patch.py
python scripts/run.py windows/scripts/prepare-modern-llama.py --check-converter
node scripts/prepare-shared-engine.mjs
node scripts/check-shared-engine-pins.mjs
node scripts/check-shared-engine-inputs.mjs
node scripts/check-shared-engine-lock.mjs
```

`prepare-shared-engine.mjs` はステージングと同時に `engine/shared-engine.lock.json` を再生成する。ソースを変えたら再生成したロックを同じコミットに含める。`check-shared-engine-lock.mjs` は古いロックを拒否し、`check-android-apk.py` はAPK内の `assets/shared-engine/lock.json` と突き合わせる。詳細は [Android新構成](android-shared-engine.md)。

端末内テストはplatform Instrumentationの専用runnerを使う。`:rebuilt:assembleDebugAndroidTest` で `rebuilt/build/outputs/apk/androidTest/debug/` のテストAPKを作り、指定端末へ本体APKとテストAPKを更新した後に `adb -s <serial> shell am instrument -w -r com.zeroconvert.ime.test/com.zeroconvert.ime.PortInstrumentationRunner` を実行する。`tests` が正の件数で `failures=0`、各項目がPASSであることを確認する。シェルの終了コード0だけで成功と判断しない。runnerは制御した入力欄を開き、IME画面を操作する。通常の利用アプリの文書は使わず、既定IMEを変更しない。`:rebuilt:connectedDebugAndroidTest` の0件/NO-SOURCEを成功の代わりにしない。

既存データで精度も測定する場合は同じrunnerへ `-e quality true` を追加する。基本動作の検査に補正35例・正常入力8例・先読み30例の検査を加え、`files/port-quality.jsonl` に合成入力の測定結果を保存する。正常入力への不要な補正提案は検査失敗になる。精度値と機能検査を分けて報告し、期待値との不一致を単なるビルド成功で覆わない。学習はプロセス内で抑止し、利用者の学習モード設定を一時変更しない。画面を点灯・解除してアプリを前面にした状態で実行する。runnerの画面起動待ちには10秒の上限がある。検証用Activityが前面にある間だけKEEP_SCREEN_ONを使い、永続的な画面設定は変更しない。

不具合テストでは、入力→補正/先読み待ち→採用→追加入力→削除→左右移動/範囲選択→確定/取消を順に再現する。フォーカス移動・キーボード表示/非表示・画面回転・入力欄やアプリの切替で古い結果を反映しないことも確認する。

## 試験端末への反映

端末更新が依頼されたときだけ行う。単にルール整備やAPK作成を依頼された段階では、接続端末へインストールしない。

1. `adb devices -l` で接続状態を確認し、依頼対象の端末を一つ選ぶ。複数ある場合は勝手に選ばず、特定できる情報を確認する。
2. 作成したAPKのapplicationId・版・署名・ABIと端末の現在のアプリを確認する。
3. 以後は `adb -s <serial>` で対象を固定する。
4. 同じapplicationId・互換性のある署名のAPKで更新する。

```powershell
# android/内。serialは確認した対象へ置き換える。
$androidSerial = '<対象端末のserial>'
$androidApk = (Resolve-Path '.\rebuilt\build\outputs\apk\debug\rebuilt-arm64-v8a-debug.apk').Path
Get-FileHash -Algorithm SHA256 -LiteralPath $androidApk
adb -s $androidSerial install -r $androidApk
adb -s $androidSerial shell dumpsys package com.zeroconvert.ime
```

`dumpsys package` で少なくともversionCode/versionName・更新情報を確認する。同じ版番号でdebug更新した場合は版番号だけで識別せず、インストールに渡したAPKのパス・SHA-256・更新時刻を記録する。

現在はdebug/releaseとも同じapplicationIdであり、署名が違えば相互に上書きできない。別PCのdebug鍵でも同じ問題が起こる。署名不一致・版の逆行・容量不足等で失敗したら原因を報告する。アンインストール、データ消去、ダウングレード、別ID化で自動回避しない。既存の設定・学習履歴を失わせない。

端末のIME有効化・選択が未設定なら、ユーザーがAndroid設定画面で操作する。ADBで既定IMEや端末設定を勝手に切り替えない。更新後は設定画面・キーボードを開き直し、別の入力アプリで実際の変換を確認する。入力アプリの状態や失敗に応じて確認範囲を広げるが、無関係なアプリのデータを消去・強制終了しない。

## 実IME確認の記録

| 確認項目 | 記録すること |
|---|---|
| 入力 | QWERTY/フリック、濁点・小文字、未完ローマ字、途中挿入、範囲選択、絵文字 |
| 補正 | 正常入力への誤補正、タイポの取りこぼし、採用後の入力継続、取消後の再提案、確定後の学習 |
| 先読み | 文脈・表記の維持、追加部分の妥当性、出現/採用率、補正との優先・表示安定性 |
| ライフサイクル | 入力先/アプリ切替、キーボード開閉、回転、モデル切替、破棄後の結果抑止 |
| 性能 | 端末機種・OS/API・ABI、モデル名/SHA-256・設定、表示までの遅延、引っ掛かり・発熱・メモリ |
| 更新 | 新規導入と既存版からの更新、設定・辞書・学習・モデル選択の維持 |

機能に応じて必要な確認を選び、未実施を明示する。x86_64エミュレータの機能確認とarm64実機の性能確認を分ける。テスト文には個人の本当の入力履歴を使わず、入力文を含むログを配布パッケージへ混ぜない。

## 配布用APKを作る

現行ビルド設定にはrelease署名の設定がない。`assembleRelease` だけで作られたunsigned APKは配布可能な完成品ではない。まず、維持するrelease署名鍵・保管先・更新方針を決めて署名手順を実装する。鍵の作成・交換や既存鍵のコピーは、ユーザーの依頼範囲を確認して行う。秘密鍵・パスワードをGit、ログ、コマンド引数へ平文で入れない。

配布時の手順は次の順序に固定する。

1. 前回配布版を確認し、versionCodeを増やし、versionNameを定める。SDKや署名の変更も必要なら別途検証する。
2. 対象コードをテストし、release APKをビルド・署名する。Android Studioの署名APK作成、またはレビュー済みのGradle/SDK署名手順を使う。
3. `apksigner verify --verbose --print-certs <最終APK>` 等で署名・証明書を検証する。署名後にAPKの中身を変更しない。
4. 最終APKを `dist/android/<日時>/` に固定保存し、SHA-256を計算する。
5. その**同じ最終APK**を対象端末で確認する。debug APKだけの確認をrelease確認の代わりにしない。
6. テストした同じAPKと必要な変更説明・検証記録を配布する。テスト後の再ビルド・再署名は別の成果物として扱う。

AABが必要なストア配布は、APKの直接配布と別の手順。`bundleRelease` の成功だけで署名・ストア公開・端末導入まで完了としない。公開先やアカウント操作を依頼されていなければ、ローカルの配布候補作成までとする。

配布フォルダにはSHA256SUMSと次の情報を保存する。現在、これを自動生成するAndroid専用スクリプトはないため、作成時に記録する。

- applicationId、versionCode/versionName、variant、最終APKファイル名・SHA-256・サイズ、署名証明書のSHA-256指紋。
- Gitコミットと未コミット差分の有無、ビルドコマンド、JDK/SDK/NDK/CMake/Gradle、含むABI。
- 組込みモデル・辞書の名前とSHA-256、確認端末の機種・OS/API、テスト結果・未確認点。

認証情報・個人の入力履歴・端末serial等の固有識別情報は配布用記録へ入れない。ローカルの確認記録は `.local/android-trials/<日時>/`、APK候補は `dist/android/<日時>/` とし、どちらも既存のGit除外を使う。

## Gitと完了報告

AndroidもルートのGitで管理する。コード・テスト・この手順・依存パッチ・モデルの識別情報を保存し、APK/AAB・重み・build出力・署名鍵・個人設定はコミットしない。

作業前後に既存変更と新規ファイルを確認する。コミット依頼時は今回のAndroid変更を選び、Windowsや学習の無関係な差分を混ぜない。共有フォルダのブランチを切り替えず、独立した並行作業には別worktreeか順次作業を使う。同じ端末・配布先への適用担当は一つにする。

完了報告には、編集内容、テストコマンド/結果、ビルドvariantとAPK情報、端末反映・実IME操作・署名・配布・Git保存の実施範囲と未確認点を書く。実行できない確認があっても、その前段階の成功と混同しない。

## 公式資料

- [コマンドラインでのビルド](https://developer.android.com/build/building-cmdline)
- [コマンドラインでのテスト](https://developer.android.com/studio/test/command-line)
- [アプリ署名](https://developer.android.com/studio/publish/app-signing)
- [アプリのバージョン管理](https://developer.android.com/studio/publish/versioning)

この文書の整備時点ではソース・Gradle設定との静的照合のみを行った。既存Androidアプリのビルド・単体テスト・端末インストール・署名・配布は、この文書整備の一環としては実行していない。
