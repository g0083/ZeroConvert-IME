# Android新構成とWindows共通変換エンジン

2026-10-24。従来のKotlin/C++移植とは別に `:rebuilt` アプリを追加した。
新構成も `com.zeroconvert.ime` を使い、互換署名での更新によって設定を引き継ぐ。
未検証の新構成を端末へ自動適用しない。

2026-11-08、`:app` モジュールを廃止し `:rebuilt` に一本化した。`android/app/` は
Gradleモジュールではなく、`:rebuilt` がコンパイルする共有ソースと資産の置き場として残る。
版・SDK・NDKは `android/gradle.properties` の `zerocon.versionCode` 等が正本で、
`rebuilt/build.gradle.kts` はそこから読む。二つのホストが別々の値を持つ状態をなくすため。

## 構造と変更する場所

| 層 | 正本・利用方法 |
|---|---|
| 通常変換・N-best統合・辞書補正・変換学習 | Windowsの `server-swift/Sources` をそのままAndroidへコンパイル |
| Swift変換ライブラリ | Windowsがビルドに使うvendored converter（`windows/scripts/prepare-modern-llama.py` がchecksum固定で用意し、`prepare-shared-engine.mjs` が同じ版をステージング）。パッチは `windows/scripts/converter_patch.py` が正本 |
| Swift依存のpin | `engine/Package.resolved`（コミット済み）。Windowsの `windows/server-swift/Package.resolved` と一致することを `check-shared-engine-pins.mjs` が検査する |
| タイポ補正・先読み・動的マクロ | `engine/rust` がWindowsの `crates/server/src/ai_assist` を直接コンパイル |
| 自動確定の境界・安定性判定 | `windows/crates/shared/src/conversion_policy.rs` を両ホストから利用 |
| 入力ポリシー（即確定記号・句読点正規化・数字組成・幅既定値） | 正本は `windows/crates/shared/src/conversion_policy.rs`。Androidは初期化時に `op=inputPolicy` で同じ表を取得して `InputPolicy.kt` に保持し、仕様は `tests/input-policy/cases.tsv` を両OSのテストが読む |
| 推論・JNI・ファイル配置の差 | `engine/native`、`engine/swift`、Androidの `engine/WindowsEngine.kt` |
| キーボード・設定・編集操作 | 既存Compose/Flick/設定画面/InputMethodServiceの同じソース |

`BuildConfig.WINDOWS_ENGINE` は常にtrue。変換順位、補正判定、先読みプロンプト/出力検査を
Kotlinで再実装しない。Androidの文脈取得、入力先照合、機密入力抑止、世代管理、
InputConnection操作はホストに残す。新しい文節操作モードや専用操作UIは追加しない。

原打鍵、ROMAN2KANA/DIRECT/FROZEN、composition separatorをSwiftへ渡す。
UTF-16サロゲートペアはUTF-8 ABIへ渡す前に結合する。Swiftの読みとAndroidの
読みが異なれば応答を拒否し、黙って別の読みに置換しない。

Swiftは専用ワーカーで直列化し、モデル資源の解放も同じワーカーで行う。
Windowsソースのactor注釈はAndroidモジュールでは専用Swift actorへ解決し、JNIワーカーから渡す。
Windowsから削除されたソースもステージングから除き、古い実装が残ることを防ぐ。
コルーチン取消だけで実行中のSwift推論が止まるとは扱わず、入力先と要求世代で
古い応答を排除する。Rust推論キャッシュにはモデル/語彙世代を含める。

## キーボードと既存データ

12キーフリック、QWERTY、濁点・半濁点・小文字、クリップボード、スクリーンショット、
テーマ、高さ・振動、モデル選択を既存ソースから引き継ぐ。設定とユーザー辞書は
従来のSharedPreferencesを使う。

選択表記履歴は初回のみSwiftの `LearningMemory/selection-overrides.json` へ
読みを正規化して移行する。補正学習は確定成功回数をRustへ移行し、旧来の未確認の
採用回数だけを自動補正の根拠にしない。元の設定と履歴ファイルは保持する。
変換学習のストアはSwiftの1系統だけにする。Android独自の形態素頻度バイナリ
(`files/conversion-learning-v1.bin`) と読み→表記のKotlinストアは廃止し、移行時に
V2の表記を `selection-overrides.json` へ取り込んで旧キーを削除する。
新しい変換学習はSwiftの候補IDで通知する。機密欄と学習無効時の参照/保存を抑止する。

## Windows更新の反映

```text
Windowsの正本を変更 → Windowsと共通Rustのテスト
  → Build-Android.bat
  → 同じSwift/Rustソースを2 ABIへコンパイル
  → ソース/依存/辞書とnativeバイナリの指紋を照合
  → Android回帰テスト・lint・debug APK/テストAPK
  → 指定端末に更新して同じAPKで実IME確認
```

入口はルートの `Build-Android.bat`。nativeを変更しない再梱包は
`Build-Android.bat -SkipNative`。古いnativeの梱包は `verifySharedEngine` が拒否する。
両ABIはSwiftPM作業領域を共有するため順次ビルドする。通常はARM64実機用の
`rebuilt-arm64-v8a-debug.apk` とx86_64用の `rebuilt-x86_64-debug.apk` を別々に出力する。
両方のCPUを1つに含める必要がある場合は `Build-Android.bat -UniversalApk`。
出力一覧はGradleの `output-metadata.json` を正本とし、古い `rebuilt-debug.apk` を流用しない。
ビルドは端末更新、IME選択、
release署名、Git保存を行わない。

新しいcloneでは [セットアップ](setup.md) に従ってサブモジュールとモデルを用意する。
AndroidのSwift依存は `engine/Package.resolved` から解決するので、Windowsのcheckoutを
用意しなくてもビルドできる。`-UseLocalMirrors` を付けた場合だけ、既存の
`windows/server-swift/.build/checkouts` をミラーとして使ってダウンロードを省く。
Swift変換ライブラリはこの取得に含めず、Windowsと同じvendored converterを
`prepare-shared-engine.mjs` が `prepare-modern-llama.py --converter-only` 経由で
用意する。Androidのモデル/資産準備は従来どおり
`scripts/prepare-android-assist-assets.ps1` を使う。個人のSDKパスや重みはGitに含めない。

```powershell
cargo test --locked --manifest-path engine/rust/Cargo.toml --lib -- --test-threads=1
python scripts/run.py windows/scripts/test_converter_patch.py
python scripts/run.py windows/scripts/prepare-modern-llama.py --check-converter
node scripts/prepare-shared-engine.mjs
node scripts/check-shared-engine-pins.mjs
node scripts/check-shared-engine-inputs.mjs
node scripts/check-shared-engine-lock.mjs
powershell -File scripts/build-android-shared-engine.ps1 -Abi arm64-v8a
powershell -File scripts/build-android-shared-engine.ps1 -Abi x86_64
node scripts/check-shared-engine.mjs
python scripts/check-android-native.py
# android/内
.\gradlew.bat :rebuilt:testDebugUnitTest :rebuilt:lintDebug :rebuilt:assembleDebug :rebuilt:assembleDebugAndroidTest
```

ツールチェーン不要のコマンドはCIの `shared-engine-inputs` ジョブでも実行する。
converterのパッチは `windows/scripts/converter_patch.py` のアンカーが正本で、
`prepare-modern-llama.py` はそれを適用するだけ。converterのpinを上げるときは
同じコミットでアンカーを直し、`--check-converter` で確認する。指紋の対象は
`scripts/shared-engine-inputs.mjs` が正本で、`libzenz_jni.so` に実際に
コンパイルされる `assist_jni.h` と `keyboard_typo_dictionary.inc` を含む。
未コンパイルの `mod.rs.bak` は対象外。

`engine/shared-engine.lock.json` は、そのソース群から作ったAPKを識別する記録で、
`prepare-shared-engine.mjs` が生成する。ソースを変えたら再生成して同じコミットに含める。
`check-shared-engine-lock.mjs` が古いロックを拒否し、`check-android-apk.py` が
APK内の `assets/shared-engine/lock.json` と突き合わせる。converter revision、
Swiftソース、converterソース、辞書、engineソース、nativeソース、Rust lock、
Swift pinのダイジェストを保持する。

`:rebuilt` のJVMテストは許可リストではなく除外リストで運用する。除外は
メソッド単位で、旧Kotlin補正・先読み経路（共有エンジンが代替）と、
JVMからロードできないJNI呼び出しの2種類だけ。新しいテストは自動的に実行される。

生成物は `engine/build/`。ステージングされたSwiftソースやnativeを手編集しない。
このPCはSwift6.4.1、NDK27.1.12297006、JDK17、Gradle8.7を使用する。
Windows用Swift SDKで欠けているDispatchは、同版の公式Android SDKをSHA-256照合し、
生成物へ展開する。インストール済みSDKは編集しない。版変更時はURL/checksumとAPI適合層も確認する。
[Swift公式手順](https://www.swift.org/documentation/articles/swift-sdk-for-android-getting-started.html)。

## 完了判定

同じソースのコンパイルとAndroidでの完全な動作同等性は分ける。最終APKで打鍵/フリック、
削除再入力、カーソル、補正適用/取消、先読み採用、自動確定/巻戻し、辞書、学習、
モデル/入力欄切替を確認する。精度と遅延は同じモデル・設定で比較する。
従来版の端末検査結果を新構成の成功証拠に流用しない。
OPD2415への更新と基本18項目は成功。精度検査には不合格があり、正式署名・配布は未実施。
詳細は [今回の検証記録](../reports/port-history/android-shared-engine-validation.md) を参照。
2.0.5-devの容量・速度改善とV2520Aでの検証は [性能・APK容量](android-performance-size.md) を参照。
2.0.6-devのカーソル前変換の復旧と実機24項目の検証は [カーソル変換の復旧](investigations/android-cursor-prefix-regression.md) を参照。
2.0.7-devのクリップボード候補の期限・ピン留め除外・表示時間設定は [クリップボード候補表示](investigations/android-clipboard-suggestion.md) を参照。
2.0.8-devの入力途中のnなどの誤補正防止と実機検証は [未完ローマ字の保護](investigations/android-pending-romaji.md) を参照。
2.0.9-devの未確定英字から日本語への切替修正は [モード切替時の英字保持](investigations/android-mode-switch.md) を参照。
2.0.10-devの矢印移動で勝手に確定される問題への対応は [カーソル通知の所有判定](investigations/android-cursor-ownership.md) を参照。

## Androidホストの性能と梱包

2026-10-26、2.0.5-devからCPU別APK、nativeライブラリの圧縮、依存閉包に含まれない
Swift開発用/未使用runtimeの除外を行う。圧縮ライブラリはAndroidがインストール時に
展開する。APKの転送容量は減るが、展開されたライブラリ自体の保存容量が減るとは限らない。
[Android Gradle Pluginの梱包設定](https://developer.android.com/reference/tools/gradle-api/8.3/com/android/build/api/dsl/JniLibsPackaging)。
モデルは従来と同じ4ファイルを保持し、APK内のSHA-256も検査する。
逆引き辞書はWindows正本から生成した1つをSwift/Rust/Kotlinで利用し、旧資産の重複梱包を除く。
通常の旧Android辞書は、補正の辞書照合とユーザー辞書の検査に使うため保持する。

llama.cppのpinは変更せず、公式のARM CPU variantsとCPU feature scoreで実装を選ぶ。
基本ARM実装も保持し、DOTPROD/i8mm等を非対応CPUで強制しない。JNIはAndroidの
`nativeLibraryDir`を指定してbackendをロードする。動的ロードされるbackendも
CMakeの出力一覧から記録し、依存閉包/指紋検査の対象にする。
Swift/JNIは同じ共有llama/ggmlライブラリを使う。

不要な変換は要求をキューへ入れる前の世代を保持し、Swiftの処理開始前と終了後に照合する。
`engine/native/inference_control.cpp`がSwiftからのllama_decodeに中断callbackを接続する。
中断されたKV/sessionは既存converterのstopComposition APIでリセットし、次の要求を再開する。
補正/先読み用JNIの独立した中断機構はそのまま使う。候補順位・候補数・推論回数上限・
モデル・Windowsの変換順位や依存checkoutは変更しない。
共通Swiftのパス解決では、APPDATAを使えるときに不要な一時ディレクトリ取得を実行しない。
この遅延評価はWindows正本のヘルパーへ入れ、両OSが同じ実装を利用する。
共通の変換オプションでは、同じ絵文字辞書を打鍵ごとに読み直して解析せず、解析済みの
TextReplacerを再利用する。Initialize時にキャッシュを破棄し、資産URL変更時にも読み直す。
同じパスの同梱辞書を更新した場合は再初期化/プロセス再起動で反映する。

ベンチマーク用の `SharedEnginePerformanceRunner` は合成データだけを使い、学習通知を行わない。
同じテストAPKを変更前後のアプリへ使える。通常変換の経過時間と、新版でのqueue/configure/native/decode時間を
分けて記録する。初回ロードはwarm測定から外すが、各roundの初回入力を恣意的に除外しない。
`-e diagnostics true` はWindowsに既存のperformance callbackから段階別時間も収集する。
保持するのは段階名と時間のみで、本文を含むcallback detailsは破棄する。通常利用では無効。
連続入力の取消確認は `-e cancellation true`。端末・モデル・設定を固定して比較し、
旧版の直接生成と共通エンジンの候補評価の時間を同一測定範囲とは扱わない。

```powershell
adb -s <指定端末> shell am instrument -w -r -e enginePerformance true -e cases 40 -e rounds 3 com.zeroconvert.ime.test/com.zeroconvert.ime.PortInstrumentationRunner
python scripts/check-android-apk.py
```
