# Android移植 第4段階: Windows補正規則と登録語保護

続報: [第5段階の変換範囲の伸縮・部分確定](android-windows-port-stage5.md)。この文書のAPK情報は第4段階当時の記録。

2026-10-23。[第3段階](android-windows-port-stage3.md) の続き。利用者から前段階について「今のところ問題はなさそう」と報告を受けて続行した。端末・OS・モデル・操作別の確認結果は未記録であり、今回APKの実機確認とは区別する。

## 今回の変更

- Windowsの `TYPO_TABLE` 120件、`STANDARD_VOCAB` 323件、かなの代表ローマ字表を [生成スクリプト](../scripts/generate-android-assist-vocabulary.mjs) から [WindowsTypoData.kt](../android/app/src/main/java/com/zeroconvert/ime/assist/WindowsTypoData.kt) へ生成する。既存の2,061語の語彙TSVも同じスクリプトで維持し、`--check` は両方の生成物を検査する。Windowsソース・モデル重みは変更していない。
- [WindowsTypoRules](../android/app/src/main/java/com/zeroconvert/ime/assist/WindowsTypoRules.kt) をモデルが利用できない場合の補正経路へ接続した。完全一致、最長の先頭一致、助詞・記号の境界に囲まれた文中一致を扱う。例: `せにょうしゃです` → `専用車です`、`きのうはおつくれさまでした` → `きのうはお疲れ様でした`。末尾などの変更対象外の文字は保持する。
- 促音・撥音・長音・助詞・形容詞の母音脱落を標準語彙で照合する。全文の距離1ではQWERTY隣接ミスを優先し、先頭部分では隣接ミス、次いで長い語を優先する。従来の「長ければ距離2でも最初の語を採用」は除去した。Android独自のフリック候補生成・最大32件の辞書照合は後段に残す。
- [TypoVariant](../android/app/src/main/java/com/zeroconvert/ime/assist/TypoVariant.kt) は、かなを代表ローマ字へ戻した上でWindowsと同じキーボード座標で隣接判定する。入力末尾から1文字だけ置換し、1語につき最大8変種を生成する。全体が一致しなければ長い末尾語から調べ、先頭を保持する。例: `しんずく` → `新宿`。既知の読みは別語へ変更しない。
- 正常入力の保護対象を標準323語から、読込み済み組込み2,061語とユーザー辞書の登録読みに広げた。ユーザー辞書の読込み・追加・削除で不変の参照用マップを更新し、補正側の読取りにディスクI/Oを加えない。ユーザー辞書の変更世代を [AssistRequestGate](../android/app/src/main/java/com/zeroconvert/ime/assist/AssistRequestGate.kt) に含め、更新前の候補を後から採用しない。明示的な補正学習を優先する既存の順序は維持する。
- 混在ローマ字の復元では「先頭だけ既知語なら残りも正しい」と扱わず、復元した読み全体の一致を要求する。

規則による一致だけでは自動適用を許可しない。第3段階の一意修復・確定成功履歴による根拠判定、取消、通常候補の保持を引き続き使う。モデルが完成した変更不要の応答を返した場合も、規則で覆さない。

## 検証

android/で次を実行し、成功した。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline
```

- JVMテスト72件、失敗・エラー・スキップ0件。[WindowsTypoPortTest](../android/app/src/test/java/com/zeroconvert/ime/WindowsTypoPortTest.kt) の10件を追加し、明示規則、最長一致、文中の両側保持と境界、正常語・登録語保護、音韻規則、隣接キー、末尾語補正、辞書更新による失効、モデル不在時の接続を検査した。
- lint: Error/Fatal 0、Warning 28、Information 14。警告ゼロではない。
- ルートで `node scripts/generate-android-assist-vocabulary.mjs --check` 成功。
- `git -c submodule.recurse=false diff --check --ignore-submodules=all` 成功。改行コードの警告は残る。文書のローカル参照先も確認した。
- `apksigner verify --verbose --print-certs` 成功。APK内のDEXに今回の新規規則クラスと辞書世代フィールドが含まれることを確認した。

## 今回のAPK

| 項目 | 値 |
|---|---|
| パス | `android/app/build/outputs/apk/debug/app-debug.apk` |
| SHA-256 | `de6685d63fb67925df1f1d52a5fd339ef32c0e9bac42af395395463f1ad914c0` |
| サイズ | 170,497,957 bytes |
| applicationId | `com.zeroconvert.ime` |
| versionCode / versionName | 1 / 1.0.0 |
| variant / ABI | debug / arm64-v8a、x86_64 |
| 署名 | APK Signature Scheme v2、Android Debug |
| 証明書SHA-256 | `ed89c1107e5d4c2e543bf37a18fd63acec06e8f2482e13802c7945a9578ff3a5` |
| ソース | ブランチ `feature/windows-color-themes`、HEAD `a0bd2e5` + 未コミット変更 |

APK内の4種のモデル重み、逆引き辞書、語彙TSVのSHA-256は第3段階と同じ。前段階とAPKサイズが同じでも、内容・SHA-256は異なる。版番号も同じなので、試験ではSHA-256で識別する。

ソース編集・単体テスト・lint・両ABIのAPK作成・署名確認まで実施。端末への更新・今回APKでの実IME操作、正式release署名・配布、コミット・pushは未実施。既存の無関係な変更を保持した。作業前の主要変更ファイルのみ `.local/android-port-stage4-before-20261023/` に退避している。これは全ファイルのバックアップやGit保存ではない。

## 実機で確認する内容と残り

1. `なっかた`、`にゅいん`、`せにょうしゃです`、文中の `おつくれさま` を入力し、補正候補の表記、採用・取消・追加入力・確定を確認する。モデルが変更不要と判断した場合は規則候補を出さないため、モデルと設定を記録する。
2. 正常な地名・人名・専門語や、ユーザー辞書へ登録した読みを入力し、近い別語へ補正しないことを確認する。登録語の追加・削除後、古い候補を採用しないことも確認する。
3. QWERTYとフリックそれぞれで、入力停止から候補表示までの遅延、正常入力の誤補正、タイポの取りこぼし、先読みの妥当性を前版と比較する。今回のJVMテストは実機の推論精度・JNI・InputConnectionの成功を保証しない。

文節分割・境界変更と学習、Swift lattice内のタイポ修復、原打鍵境界を含む自動確定履歴の完全な互換性、外部文脈の再取得、Windowsのユーザー語彙ファイルとの交換・複数表記管理などは残る。今回はAndroidの既存ユーザー辞書へ接続した段階であり、75項目全体の移植完了ではない。
