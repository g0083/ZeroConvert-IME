# Androidキーボードの表示安定化

2026-10-26。従来の `:app` と共通エンジン版 `:rebuilt` が共用する入力UIを変更。
変換・補正・先読みの順位やモデル、設定キーは変更しない。

変更ファイルは `ComposeKeyboardView.kt`、新設 `KeyboardCandidateArea.kt`、
debug専用 `PortTestActivity.kt`、`PortInstrumentationRunner.kt`、新設 `KeyboardUiRegression.kt`、
この文書と `android-development.md` の探索先表。
既存の未コミット変更と新構成を維持し、ブランチ `feature/windows-color-themes` は切り替えていない。

## 症状と変更

- 原文・AI提案・補正取消の表示を別々に追加していたため、非同期結果の到着時にIMEの高さが変化していた。
- 原文・AI提案には横幅の配分と1行制限がなく、長い文章で縦に伸びる可能性があった。
- 補正取消は小さい説明文のタップで、AI提案の下へ追加されていた。
- 候補一覧はキー領域の上へ200dp追加され、開閉でIMEの高さが変わっていた。

`KeyboardCandidateArea.kt` の `KeyboardAssistBar` は48dpを常に確保する。
原文・提案は幅を分けて1行・省略表示とし、全文はアクセシビリティ説明に残す。
取消は同じ行の80×48dpの「戻す」ボタンにする。通常候補のバーも48dp固定。
`KeyboardContentArea` は既存のキー高さ設定から決めた領域を保ち、候補一覧とキーを切り替える。
候補一覧の各セルも1行・48dpで、見えていないキーへタップが通らない構造にする。
候補がなくなれば一覧の展開状態を解除する。

## 回帰テスト

`KeyboardUiRegression.kt` はdebug専用 `PortTestActivity` に製品と同じ表示部品と
QWERTY/12キーフリックを描画する。モデル・ユーザー設定・既定IMEの変更は不要。

```powershell
# android/内
.\gradlew.bat :app:testDebugUnitTest :rebuilt:testDebugUnitTest :rebuilt:lintDebug :rebuilt:assembleDebug :rebuilt:assembleDebugAndroidTest
# 対象はその都度確認して固定する
adb -s <対象serial> shell am instrument -w -r -e keyboardUiOnly true com.zeroconvert.ime.test/com.zeroconvert.ime.PortInstrumentationRunner
```

検査はキー高さ40/46/64dp、フォント倍率1/2、空欄→入力→長い原文→先読み→補正提案→
補正済み＋先読み→一覧開閉→空欄の順で、領域とキーの座標を比較する。
押下中の提案到着でも元のキーが1回だけ入力されること、採用と取消のタップも確認する。
実IMEの入力先との連携は、同じAPKで既存 `PortInstrumentationRunner` の通常検査も行う。
部品のテストだけで日常入力の体感や実機性能の成功としない。

## 検証記録

単体テストは `:app` 124件、`:rebuilt` 17件成功。`:rebuilt:lintDebug` は0 errors / 28 warnings。
arm64-v8a/x86_64の既存共通nativeの指紋・依存閉包を照合してdebug APKを作成した。

OPD2415 / Android16 / API36 / arm64-v8aを署名互換のdebug APKで更新した。
設定・学習データ・既定IMEを保持し、端末内のbase.apkと固定したAPKのSHA-256一致を確認。
UI回帰5件は成功し、QWERTY/フリック、キー高さ3種類、フォント倍率2種類で
長文・候補の出入り・一覧開閉・押下中の更新・採用・取消を検査した。
既存の実IME基本検査18件も成功。実キーの入力・削除・再入力・カーソル往復・確定で
座標が変わらない検査を既存の `real_ime_keys_and_commit` に追加した。
長押し対応のカーソル/削除キーにはclickアクションがないため、実際のDOWN/UPで検査する。
この追加検査の最初のclick呼出し失敗は、実タッチへ修正して18件すべて再成功を確認した。

APKは `com.zeroconvert.ime` / `2.0.0-dev` / versionCode 2、debug署名、arm64-v8a/x86_64。
固定したファイルは `.local/android-ui-trial-20261026/zerocon-ui-debug.apk`。
SHA-256は `41f0cc956dfb4d7d5658dab7db02ef7ff6cf5287570194eae898b4ee6d6cc063`、
証明書SHA-256は `ed89c1107e5d4c2e543bf37a18fd63acec06e8f2482e13802c7945a9578ff3a5`。
正式署名・配布・Gitコミット/pushは行っていない。

初回のエミュレーター検査はOSの応答停止ダイアログで失敗した。
実機での初回検査にはアクセシビリティキャッシュと別IMEウィンドウの参照による
検査側の失敗があり、対象画面に限定してキャッシュを更新し再検査した。
これらは最終実機検査の成功と区別する。

アプリAPK作成時の共通native指紋照合は成功した。その後、別作業でWindowsの
`azookey_server.swift` が更新され、現作業ツリーとの指紋照合は不一致になった。
その変更は今回のUI修正APKには含めない。検証済みのアプリAPKを上記SHA-256で固定し、
以後はnativeを含まないテストAPKだけを `:rebuilt:assembleDebugAndroidTest -x :rebuilt:verifySharedEngine`
で作成した。次にアプリAPKを作るときは、通常手順で両ABIを再ビルドして照合する。
