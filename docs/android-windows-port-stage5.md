# Android移植 第5段階: 変換範囲の伸縮と部分確定

2026-10-24訂正: この段階で追加した専用の「範囲変換」操作UIは、Windows版のロジック移植を超えた独自追加だったため、ユーザーの指摘を受けて取り下げた。以下は当時の記録。既存のカーソル前部分確定と、その不具合修正は保持する。

続報: [第6段階の実機検証と部分確定の修正](android-windows-port-stage6.md)。この段階のAPKは端末未確認だったが、その後VIVOで末尾が確定扱いになる競合を再現し、第6段階で修正・回帰確認した。

2026-10-23。[第4段階](android-windows-port-stage4.md) の続き。Windowsの文節操作に向けて、利用者が先頭の変換範囲を指定し、部分確定できる入口を追加した。自動の文節分割や複数文節の往復操作は未実装。

## 今回の操作と実装

1. 日本語を入力し、候補の上の「範囲変換」を押す。最初は末尾1コードポイントを除いた先頭部分が対象になる。
2. `範囲: とうきょう｜にいく` のように境界が表示される。「縮める」「伸ばす」または左右キーで対象を調整する。空の範囲にはせず、全体まで伸ばせる。絵文字のサロゲートペアは分断しない。
3. 候補を選ぶと先頭部分だけを確定し、末尾を未確定入力として残して変換を続ける。例: `とうきょう｜にいく` から「東京」を選ぶと、「東京」が確定して `にいく` が残る。成功した明示的な候補選択では、対象の読みと表記だけを既存の選択学習へ保存する。Enterによる通常確定は明示選択の学習には数えない。
4. 確定前の「取消」は、範囲指定前の原打鍵・読み・カーソルを復元する。文字の入力・削除・濁点変更等を行った場合は範囲モードと取消用スナップショットを終了し、その編集を続ける。編集後に古いスナップショットへ戻さない。

範囲内の変換は辞書・ユーザー辞書・選択学習を使い、モデル変換・補正・先読み・自動確定を抑止する。対象の読みと一致する候補だけを表示し、未入力部分まで補完する辞書候補を除外する。境界変更時は古い候補を消し、要求世代とモードを検査して結果を反映する。

通常入力の末尾で、読みが2〜128コードポイント、未完ローマ字がない場合に開始できる。機密入力欄、選択文字列の再変換中、採用済み補正を保護中は入口を出さない。QWERTYとフリックに共通の操作。境界をローマ字から変換された拗音の途中に置いて確定した場合、既存のComposingTextの処理で必要な単位をかなへ固定し、消費しない末尾を保持する。

範囲内へ入力した際、ライブ変換ONで末尾が一時的に表示から消える既存経路も修正した。打鍵・かな入力・濁点変更・かな置換の即時表示に末尾を加える。

主な変更: [ConversionRangeSession](../android/app/src/main/java/com/zeroconvert/ime/ConversionRangeSession.kt)、[入力サービス](../android/app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt)、[共通キーボードUI](../android/app/src/main/java/com/zeroconvert/ime/ComposeKeyboardView.kt)、[要求ゲート](../android/app/src/main/java/com/zeroconvert/ime/assist/AssistRequestGate.kt)。Windowsソース・native辞書・モデル重みは変更していない。

## 検証

android/で次を実行し、成功した。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline
```

- JVMテスト80件、失敗・エラー・スキップ0件。[ConversionRangeTest](../android/app/src/test/java/com/zeroconvert/ime/ConversionRangeTest.kt) の8件を追加した。原打鍵の保存・伸縮・取消、部分確定後の入力と削除、サロゲートペア、拗音内の確定、候補の読み一致、編集後の取消抑止、要求モード変更、末尾の即時表示を検査する。
- lint: Error/Fatal 0、Warning 28、Information 14。
- `apksigner verify --verbose --print-certs` 成功。APK内DEXに範囲変換クラスが含まれることを確認した。
- `git -c submodule.recurse=false diff --check --ignore-submodules=all` と文書のローカルリンクを確認。改行コードの警告は残る。

## 今回のAPK

| 項目 | 値 |
|---|---|
| パス | `android/app/build/outputs/apk/debug/app-debug.apk` |
| SHA-256 | `24a3cd45e7d2d486a64dc12c1e070c4aca6170d9e85ab601fff65c0769f8d3e8` |
| サイズ | 170,497,957 bytes |
| applicationId | `com.zeroconvert.ime` |
| versionCode / versionName | 1 / 1.0.0 |
| variant / ABI | debug / arm64-v8a、x86_64 |
| 署名 | APK Signature Scheme v2、Android Debug |
| 証明書SHA-256 | `ed89c1107e5d4c2e543bf37a18fd63acec06e8f2482e13802c7945a9578ff3a5` |
| ソース | `feature/windows-color-themes`、HEAD `a0bd2e5` + 未コミット変更 |

モデル4種・逆引き辞書・語彙TSVのSHA-256は第4段階と一致。APKのサイズ・版番号が前版と同じなので、試験APKはSHA-256で識別する。

ソース編集、JVMテスト、lint、両ABIのdebug APK作成、署名・内容確認まで実施。今回APKの端末更新・実IME操作、release署名・配布、コミット・pushは未実施。既存の無関係な変更を保持した。今回編集前の主要3ファイルのみ `.local/android-port-stage5-before-20261023/` に退避しており、全体のバックアップやGit保存ではない。

## 実機で確認する内容・残り

- QWERTYで `toukyouniiku`、フリックで `とうきょうにいく` を入力し、`とうきょう｜にいく` まで縮めて「東京」を選ぶ。末尾の表示、追加入力・削除・残りの確定を確認する。同じ読みを再入力して選択学習も確認する。
- 伸ばす・縮めるを連続操作し、古い範囲の候補が戻らないことを確認する。全体まで伸ばした場合、候補を待たずEnterした場合、取消した場合も試す。
- ライブ変換ON/OFFそれぞれで、範囲内への入力・濁点変更、拗音・絵文字、アプリ切替・キーボード開閉を確認する。取消前後と部分確定後の文書に欠落・重複がないことを確認する。

JVMテストではInputConnection、候補UIの実際の表示、学習保存先へのAndroid上のI/O、JNI辞書の動作を保証しない。端末・入力アプリ・モデル・設定を記録した実IME確認が必要。

自動文節分割、文節ごとの候補スナップショットと前後移動、Swift lattice内のタイポ修復、境界自体の学習、原打鍵境界を含む自動確定履歴の完全互換性などは引き続き残る。75項目全体の移植完了ではない。
