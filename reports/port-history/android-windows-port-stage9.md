# Android移植 第9段階: 通常確定の学習経路

2026-10-24。Windowsの `composition.rs::commit_enter_actions` は通常Enterでも `CommitLearning` を発行する。Androidは候補タップだけを記録していたため、通常確定も対象へ接続した。

## 変更

- `ConversionCommitLearning` がエディター呼び出し前に読み・選択表記を固定する。候補の読みとの一致、学習可否、機密欄、適用済み補正を確認する。候補不明の記号入力や、読みの異なる古い候補は記録しない。
- `confirmAsync` の再推論経路は、その応答の候補を渡す。画面に残っている別の候補から記録対象を探さない。句読点を付けて確定した場合は、選択した表記だけを学習する。
- `InputConnection.commitText` 成功後に限り、上限128件のChannelへ渡す。1本のIO処理で確定順に保存し、実行時にも学習モードを確認する。入力処理でディスク保存を待たない。
- 学習モードはWindowsと同じtrim・小文字化・ハイフン正規化を行い、`readonly`、`no_new_learning` も読み取り専用として扱う。既存のAndroid enum名と保存値も維持する。
- 現在のソースと既存実機テストの不整合を修正した。登録語をKotlin→JNI→nativeラティスへ渡し、文中の一致位置でも使う。CID1288/MID501、値 `-5-index*0.01`、最大50件。登録語と入力スタイルを含む構造化キャッシュキーで、登録語なし・置換後の検索結果を分離する。

主な編集先は `ConversionCommitLearning.kt`、`LearningPolicy.kt`、`ZeroConvertInputMethodService.kt`、`KanaKanjiConverter.kt`、`ZenzNative.kt` とnative辞書/JNI。Windowsソースは今回変更していない。

## 検証と成果物

android/で以下を実行して成功した。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline
```

JVM111件、失敗・エラー・スキップ0。lintエラー0、警告28。候補固定、句読点分離、読み不一致、候補不明、補正・機密欄・書込禁止の除外をJVMで確認した。

OPD2415へ同じ署名で `install -r` し、実IMEのキーと確定を含む基本15件が成功した。最初の実行では初回辞書補正の呼び出しが空を返して1件失敗した。辞書のbusy/cancelが空を返すAPIに合わせ、既存の辞書検査と同じ最大4秒の非空結果待ちをテストへ追加した。期待する補正語・属性の検査は維持している。最初の失敗ログも保存した。

精度検査を含む18件は1件失敗。補正9/35、先読み8/30、正常入力への不要提案2/8が残った。今回の修正で精度が改善したとは判定しない。

| 項目 | 値 |
|---|---|
| 固定APK | `.local/android-trials/20261024-normal-commit/app-debug.apk` |
| SHA-256 | `658e51767984d74247f72c07489d747be2fa2e5ab418dae5210c99188e00d44f` |
| サイズ | 170590656 bytes |
| variant / 版 / ABI | debug / versionCode1、versionName1.0.0 / arm64-v8a、x86_64 |
| 証明書SHA-256 | `ed89c1107e5d4c2e543bf37a18fd63acec06e8f2482e13802c7945a9578ff3a5` |
| 実機APK照合 | インストール先base.apkのSHA-256が上記と一致 |
| 記録 | 同フォルダの `build-info.json`、`instrumentation-result.txt`、`instrumentation-first-result.txt`、`instrumentation-quality-result.txt`、`port-quality.jsonl` |

## 残件と適用範囲

通常変換の保存形式は既存の読み→表記500件を維持している。Windowsの形態素単位・文節連接・全文の頻度学習、日数による減衰と失効、nativeでの学習辞書照合は未移植。C07全体は未完。自動確定の学習と巻戻しの扱いも残る。

端末テストは合成入力を履歴に残さないようプロセス内の書込抑止を使っている。そのため、実ユーザー履歴への新規保存・再起動後の順位を実機で確認した証拠ではない。今回の成功を学習全体の完了にしない。

ソース編集・JVM/lint・debugビルド・OPD2415更新・実IMEの基本操作まで実施。release署名・配布・commit/pushは未実施。以前のVIVOでの学習モード復元待ちは継続し、元値を推測して変更しない。
