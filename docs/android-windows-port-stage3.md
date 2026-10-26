# Android移植 第3段階: 自動補正・逐次候補・再変換

続報: [第4段階のWindows補正規則・登録語保護](android-windows-port-stage4.md)。後日、利用者からこの段階について「今のところ問題はなさそう」と報告を受けた。端末・OS・モデル・個別操作の記録はなく、網羅的な品質検証を意味しない。

2026-10-23。[第2段階](android-windows-port-stage2.md) の続き。[移植前75項目](windows-android-feature-gap.md) 全体の完了ではない。

前回のビルドについて利用者から「試しましたが大丈夫そうです」と報告を受けた。端末・OS・モデル・APKの識別情報と操作別の結果は未記録であり、以下の第3段階APKの実機確認とは区別する。

## 実装した内容

- **自動補正**: 一意に決まる隣接キーの1文字修復、または同じ読み・表記を明示採用して確定成功した履歴が3回以上ある場合に限定する。既知の正常読み、未完ローマ字、英字だけの識別子、曖昧な修復を保護する。設定「自動タイポ補正」は既定で有効。補正済み表示のタップまたはBackspaceで元へ戻せる。自動適用だけでは学習しない。
- **学習の移行**: 旧履歴の回数・手動候補は保持し、自動適用用の確定成功回数を別に保存する。旧回数を自動適用の根拠にせず、新たな確定成功を数える。異なる表記への変更時は回数をリセットする。
- **語彙**: Windowsの標準語彙・固有名詞・組込み語彙から、読み2,061件、うち正常入力保護323件を生成する。全文字列一致の辞書として使用する。正常入力保護用語彙と補正先候補の語彙は区別する。読み込みに失敗した場合は一意修復による自動適用を抑止する。
- **逐次表示**: 補正と先読みを独立に受け取り、先読みが完成したら表示する。後から届く補正で表示中のバナーの採用対象を交換しない。補正は通常候補列にも追加し、通常候補の順序・識別を保持する。根拠のある自動補正は先読みの完了を待たない。
- **通常変換**: 読みより長い出力と正当な繰返しを残し、従来の文字数切捨て・同字反復の圧縮を除去した。nativeの通常変換はcontextを1,024に広げ、読み長に応じた生成上限と1,200msの期限・取消世代を使う。未完・失敗時は辞書へ戻す。JNI文字列はUTF-8とUTF-16を明示変換する。
- **再変換**: 未変換入力がない状態で既存テキストを選択すると、ツールバーの「再変換」から逆引きした読みの辞書候補を出す。この経路はモデル変換・補正・先読み・自動確定を抑止する。元表記を先頭に残し、候補選択まで表示を保つ。読みが不明なら文書を変更しない。
- **編集状態の検査**: 選択範囲・入力先・要求世代・設定が変わった結果は反映しない。外部から未確定範囲の外へカーソルを動かした場合は管理中の入力状態を終了する。

主な入口は [入力サービス](../android/app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt)、[候補UI](../android/app/src/main/java/com/zeroconvert/ime/ComposeKeyboardView.kt)、[通常変換](../android/app/src/main/java/com/zeroconvert/ime/KanaKanjiConverter.kt)、[native通常変換](../android/app/src/main/cpp/zenz_jni.cpp)。

追加した独立ロジックは [AutoCorrectionEvidence](../android/app/src/main/java/com/zeroconvert/ime/assist/AutoCorrectionEvidence.kt)、[AssistPresentation](../android/app/src/main/java/com/zeroconvert/ime/assist/AssistPresentation.kt)、[候補統合](../android/app/src/main/java/com/zeroconvert/ime/ConversionCandidateMerge.kt)、[再変換計画](../android/app/src/main/java/com/zeroconvert/ime/ReconversionPlan.kt)、[語彙生成](../scripts/generate-android-assist-vocabulary.mjs)。Windowsのコード・モデル重みは変更していない。

## 検証とAPK

android/で実行:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline
```

- 成功。JVMテスト62件、失敗・エラー・スキップ0件。追加の [AutomaticAssistTest](../android/app/src/test/java/com/zeroconvert/ime/AutomaticAssistTest.kt) は、自動補正の根拠・曖昧性・旧履歴移行・取消と学習・逐次表示・候補順序・繰返し表記・再変換計画を検査する。
- lint: Error/Fatal 0、Warning 28、Information 14。警告が残るため警告ゼロとは扱わない。
- `node scripts/generate-android-assist-vocabulary.mjs --check` 成功。生成元との一致を確認した。
- `apksigner verify --verbose --print-certs` 成功、APK Signature Scheme v2。debug証明書SHA-256: `ed89c1107e5d4c2e543bf37a18fd63acec06e8f2482e13802c7945a9578ff3a5`。

| 項目 | 今回の成果物 |
|---|---|
| APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| サイズ | 170,497,957 bytes |
| SHA-256 | `516161d9ed30d6a741a91977bc0eaa21817a2683e67141b64582ca26a9dd843e` |
| applicationId | `com.zeroconvert.ime` |
| 版・variant | versionCode 1 / versionName 1.0.0 / debug |
| ABI | arm64-v8a、x86_64 |
| Git | `feature/windows-color-themes`、HEAD `a0bd2e5` に未コミット変更を含む |

APK内のCorrector、Nano、Standard、Nano Lite、逆引き辞書のSHA-256は第2段階の記録と一致した。新規 `assets/assist-vocabulary.tsv` は73,409 bytes、SHA-256 `38b38f3ecc58251a285878b47c9ef2289edda35d0581169b35a11490382205aa`。語彙TSVはGit対象のテキストで、重み・逆引き辞書・APKは既存の除外対象。

ソース編集、JVMテスト、lint、両ABIのdebugビルド、署名・内容検証まで実施。端末更新、今回APKでの実IME操作、release署名・配布、コミット・pushは未実施。既存の無関係な変更を保持した。作業前の主要ファイルのローカル退避は `.local/android-port-stage3-before-20261023/` にあり、全ファイルのバックアップやGit保存の代わりではない。

## 今回APKでの実機確認

1. 正常な日本語・英字識別子・未完ローマ字を入力し、不要な自動補正が出ないことを確認する。タイポ補正が出たらタップとBackspaceで取消し、追加入力・確定まで試す。自動補正設定のON/OFFも確認する。
2. 補正と先読みを待ち、表示中の候補が遅れて別の採用対象へ変わらないこと、通常候補も選べることを確認する。待機中の削除再入力・カーソル移動・アプリ切替も試す。
3. 確定した漢字列を選択して「再変換」を押す。元表記維持、別候補の採用、読み不明の文字列を確認する。利用する入力アプリごとに選択範囲と未確定範囲の挙動を確認する。
4. 繰返し表記・長めの読み・絵文字を含む入力を確認する。モデルと設定を記録し、停止から候補表示までの遅延、引っ掛かり、メモリ・発熱を前版と比較する。nativeのcontextと生成予算変更の実機負荷は未検証。

JVMテストではJNI推論・InputConnection・実機の表示や精度を保証できない。正常入力の誤補正率、タイポの取りこぼし、先読みの妥当性・遅延の比較測定は残る。

## 残る移植

WindowsのSwift latticeに統合されたタイポ修復、文節の分割・境界変更と学習、原打鍵境界を含む自動確定履歴の完全な互換性、補正規則表全体・ユーザー語彙の同等性、推論結果反映時の外部文脈再取得などは未完。今回の辞書再変換はWindowsの文節操作全体を再現するものではない。75項目の完全対応と品質評価は引き続き必要。
