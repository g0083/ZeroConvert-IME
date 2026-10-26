# Android版 エージェントガイド

ルートの [AGENTS.md](../AGENTS.md) に加えて適用する。編集・テスト・端末反映・配布の手順の正本は [Android開発手順](../docs/android-development.md)。Android作業でWindowsソース全体を探索したり、WindowsのBuild-Trial/Apply-Trialを実行したりしない。

## 作業の入口

- Gitはリポジトリルートで管理する。作業前後にブランチ・既存変更・新規ファイルを確認し、既存Android変更を自分の変更と混同しない。共有作業フォルダを切り替えたり、reset/restore/clean/stashで整えたりしない。
- Windowsからの移植は「機能がある」「入力経路に接続済み」「テスト済み」「端末で動作確認済み」を分けて調べる。WindowsのRust/Swiftコードや時間予算を、そのままAndroidで動く前提にしない。
- Androidで `azookey-dev-flow` を必須にしない。同スキルはWindows用であり、AndroidではこのガイドとAndroid開発手順を使う。

## 主な編集先（android/からの相対パス）

| 対象 | 最初に読むファイル |
|---|---|
| 入力・確定・削除・カーソル・ライフサイクル | `app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt` |
| 打鍵・読み・カーソルの状態 | 同 `azookey/input/ComposingText.kt` |
| かな漢字変換・候補統合 | 同 `KanaKanjiConverter.kt` |
| 補正・先読み・採用学習 | 同 `assist/` |
| キーボード・候補表示・設定 | 同 `ComposeKeyboardView.kt`、`FlickKeyboardView.kt`、`MainActivity.kt` |
| モデル配置・選択・切替 | 同 `model/ModelManager.kt`、`ZenzNative.kt` |
| JNI・推論・LOUDS辞書 | `app/src/main/cpp/zenz_jni.cpp`、`azookey_louds.cpp`、`azookey_louds.h`、`CMakeLists.txt` |
| SDK・ABI・版・ビルド | `app/build.gradle.kts`、`build.gradle.kts`、`gradle/wrapper/gradle-wrapper.properties` |
| 権限・IME登録・コンテンツ共有 | `app/src/main/AndroidManifest.xml`、`app/src/main/res/xml/` |
| JVM回帰テスト | `app/src/test/java/com/zeroconvert/ime/` |

## 修正の原則

- メインスレッドのキー処理・InputConnection操作にモデル推論、ディスクI/O、大きな辞書探索、待機を追加しない。推論・辞書・設定保存の実行スレッドと処理上限を確認する。
- 原打鍵、読み、通常表記、補正後表記、先読み追加部分、カーソル位置、候補の識別を分ける。表示だけ直して内部ComposingTextが原文のままになる実装を避ける。
- 削除再入力で同じ読みになっても古い推論結果を適用しない。要求世代・入力状態・入力先・文脈・設定・モデルの変更を確認し、取消だけでnative処理まで停止すると仮定しない。
- onStartInput/入力ビュー開始、終了、選択範囲変更、アプリ切替、画面回転、モデル切替時のcomposition・Job・JNI資源の扱いを確認する。破棄済みInputConnectionへ結果を反映しない。
- QWERTYと12キーフリック、濁点・半濁点・小文字、末尾未確定ローマ字、自動確定の巻戻し等の既存動作を保持する。
- 設定はキー名・型・既定値・設定画面・読み直し・モデルへの受渡しをそろえる。既存設定・ユーザー辞書・補正学習・モデル選択の移行を確認する。パスワード欄等の学習・補正・先読みの扱いは明示的に検証する。
- `app/src/main/cpp/llama.cpp/` は外部依存。独自変更はルートの `patches/llama.cpp/` に保持し、既存setupスクリプトとgitlinkに整合させる。依存取得・パッチ再適用を無条件に行わない。
- APK/AAB、build/.cxx/.gradle、重み、端末内に展開されたモデルを直接修正しない。署名鍵・認証情報・個人の入力履歴はGitや配布物に含めない。

## テストと端末反映

- Windows上ではandroid/でGradle wrapperを使う。製品コード変更の基本確認は `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。変更範囲に応じてテストを絞る場合は選定理由を記録する。文書のみならリンク・参照内容・差分確認でよい。
- 入力不具合は実際の打鍵/フリック、待機、採用、追加入力、Backspace/Delete、カーソル・範囲選択、確定/取消、入力先切替の順序を再構成し、必要な回帰テストを追加する。
- JVMテストではAndroid APIの既定値返却が有効で、JNIロード失敗を捕捉する経路もある。成功だけでnative辞書・モデル・InputConnection・IMEライフサイクルの成功と判断しない。
- `app/src/androidTest/` の `PortInstrumentationRunner` はplatform Instrumentationを使い、debug専用の `PortTestActivity` でInputConnection・native資産・実IME操作を検査する。`assembleDebugAndroidTest` でテストAPKを作り、対象を固定して `am instrument -w -r` で実行し、tests/failuresと個別結果を確認する。connected testタスクのNO-SOURCE/0件を成功としない。テストにはIMEの画面表示と合成入力を使うため、指定端末だけで実行する。
- `assembleDebug` はAPK作成まで。端末は更新されない。端末反映が依頼されたら対象を `adb devices -l` 等で確認して `-s` で固定し、APK・署名・版を確認して更新する。複数端末へ一括インストールしない。
- 更新は同じapplicationId・互換性のある署名のAPKで `adb -s <serial> install -r <apk>`。失敗をアンインストール、アプリデータ消去、ダウングレード指定で自動回避しない。設定・学習・IME有効化状態を失わせない。
- 更新後は実際のversionCode/versionNameとインストール元APKを照合し、設定画面と別の入力アプリでIMEを確認する。デバイスがなければAPKビルドまでと報告し、実IME確認済みとしない。
- 機能精度は正常入力の誤補正・タイポの取りこぼし・先読みの妥当性と出現率・停止から表示までの遅延を比較する。端末/OS/ABI/モデルSHA-256/設定を記録し、x86エミュレータだけでarm64実機性能を保証しない。

## 配布とGit保存

- 現行app/build.gradle.ktsはrelease署名設定なし。`assembleRelease` 成功やunsigned APKを、そのまま配布可能と報告しない。debug APKは開発・限定試験用、正式配布は管理したrelease鍵で署名・検証する。
- 配布時は前回配布よりversionCodeを増やし、versionNameを明示する。applicationIdと更新用署名の互換性を保持する。鍵を勝手に新規作成・交換しない。
- 最終署名済みAPKを固定保存し、その同じAPKを端末でテストして配布する。テスト後に再ビルド・再署名した場合は別成果物として再確認する。AABは直接端末インストールするAPKとは別物。
- 配布候補はルートの `dist/android/<日時>/` に保存し、SHA-256、アプリID、版、署名証明書指紋、Gitコミット/未コミット差分、ABI、モデル情報、テスト結果を記録する。入力履歴・端末固有識別情報・鍵の秘密値は配布記録に入れない。
- APK作成・端末反映・ファイルの配布・ストア公開・Gitコミット・pushは別操作。ユーザーが依頼した範囲を守り、Git保存はルートルールと既存承認に従う。
- 完了時は、編集ファイル、実行コマンド/結果、ビルドvariant・版・ABI・APKパス/SHA-256、端末反映/実IME確認/配布/Git保存の有無、残る未確認点を報告する。今のCIはAndroidのビルド・端末検証を実行していない。
