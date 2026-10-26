# Android移植 第6段階: 全件監査・自動確定・実機テスト

この文書は当時の記録。2026-10-24に独自の範囲・文節操作UIを取り下げた。現在の対象・APK・検証結果・残作業は [第7段階](android-windows-port-stage7.md) と [完了監査](android-port-completion-audit.md) を参照し、末尾の当時の次作業を現在の仕様と解釈しない。

2026-10-24。[第5段階](android-windows-port-stage5.md) の続き。「全部の作業を終わらせて」の依頼により、[75項目の完了監査](android-port-completion-audit.md) を開始した。全体は作業中であり、この段階を全移植の完了とは扱わない。

## 今回の修正

- **原打鍵境界**: ComposingTextに非破壊の境界判定を追加した。自動確定が拗音のローマ字単位やサロゲートペアの途中を切らないよう、候補を選ぶ時と確定直前に検査する。
- **安定履歴**: 同じプレビューの連続応答を独立した安定ステップに数えない。原打鍵数と読み長に対応するsnapshotを更新し、保持数を128件へ制限した。
- **巻戻し**: 確定前の原打鍵を含むComposingText全体を保持する。周辺の実文字列を照合し、未確定の末尾を一旦除いてから確定部分を削除する。元の入力をFROZENかなへ一律変換せず復元する。エディターが削除を拒否した場合は末尾を戻す。最終の再設定を拒否する場合も元入力を内部に保持し、入力の再設定を試みる。
- **モデルの同一性**: 組込みモデル3種にSHA-256を定義し、既存ファイルと展開した一時ファイルを検証してから置き換える。同サイズでも重みの変更を検出する。保存済み選択先が管理下の組込みモデルであれば起動時にも検証し、同名の外部ファイルを上書きしない。
- **辞書の形態素情報**: Viterbi候補から各形態素の読み・表記・品詞接続ID・意味ID・コストをJNI経由で渡す。Kotlinで全体の読みと表記が一致することを検査して採用する。後続の文節処理のためのデータで、文節の往復操作自体はまだ未実装。
- **辞書の探索範囲・取消**: 複合語の探索上限を32から128コードポイントへ拡張した。長文でViterbi探索しない場合も直接完全一致の辞書語を除外しない。辞書要求はtry-lock、最大64候補、250msの期限と取消世代を持ち、未完・busyの結果をキャッシュしない。端末ごとの長文精度・遅延の測定は残る。
- **部分確定の実機不具合**: VIVOの実IMEテストで、先頭だけを確定した後に末尾のcomposing spanが消える不具合を再現した。確定と末尾の再設定の間の選択通知が外部移動と判定されていたため、両操作をバッチ編集にまとめた。手動の部分確定と自動確定の両方へ適用した。
- **実機テスト経路**: platform Instrumentationのrunnerとdebug専用入力欄を追加した。フリックキー・通常キーにアクセシビリティ入力操作、候補と入力モードに識別可能な説明を追加し、実IMEの操作を検証できるようにした。タッチ/フリックの処理は保持する。

主な追加ソース: [AutoCommitRewind](../android/app/src/main/java/com/zeroconvert/ime/AutoCommitRewind.kt)、[VerifiedModelFile](../android/app/src/main/java/com/zeroconvert/ime/model/VerifiedModelFile.kt)、[DictionarySegments](../android/app/src/main/java/com/zeroconvert/ime/DictionarySegments.kt)、[PortInstrumentationRunner](../android/app/src/androidTest/java/com/zeroconvert/ime/PortInstrumentationRunner.kt)、[検証用Activity](../android/app/src/debug/java/com/zeroconvert/ime/PortTestActivity.kt)。

## ビルドとホスト検証

android/で実行:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline
```

- 成功。JVMテスト90件、失敗・エラー・スキップ0件。[PortIntegrityTest](../android/app/src/test/java/com/zeroconvert/ime/PortIntegrityTest.kt) 7件、[DictionarySegmentsTest](../android/app/src/test/java/com/zeroconvert/ime/DictionarySegmentsTest.kt) 3件を追加した。
- lint: Error/Fatal 0、Warning 28、Information 14。
- arm64-v8a/x86_64のnativeビルド成功。語彙生成の `--check`、Gitの `diff --check --ignore-submodules=all` も成功。改行コードの警告は残る。

## VIVOでの実機確認

利用者がVIVO端末への更新・検証を指定した。対象はvivo V2520A、Android 16 / API 36。既定IMEは既にZeroConvertであり、切り替えていない。更新前APKはSHA-256が第3段階と一致し、同じdebug署名・versionCodeで `install -r` 更新した。アンインストール・データ消去・ダウングレードは行っていない。

```text
PASS editor_setup
PASS partial_commit_keeps_tail
PASS real_input_connection_rewind
PASS native_assets_and_model_load
PASS native_dictionary_morphology
PASS reverse_dictionary_and_privacy
PASS real_ime_keys_range_and_partial_commit
Tests: 7, failures: 0
```

最終の実IME項目は、制御した入力欄で5文字入力→範囲変換→縮小・拡大→取消→再び範囲変換→表示中の候補選択を行い、確定した先頭と未確定末尾の実文字列・composing spanを確認する。合成入力の明示的候補選択は通常の選択学習経路を通る。結果は `.local/android-trials/20261024-port/instrumentation-result.txt`、実機不具合の修正前結果は同 `before-batch-fix.txt`、ビルド情報は同 `build-info.json` に保存した。試験記録に端末serialを公開しない。

これは上記7項目の確認であり、全タイポ/予測の精度、長文、他の入力アプリ、ライフサイクル全組合せを確認した意味ではない。精度・遅延の比較測定、文節処理の完成と追加実機検証は残る。

## 今回の検証済みAPK

| 項目 | 値 |
|---|---|
| APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| SHA-256 | `82554668f47e10fb7a2774e4ae158e644a12fd9a54331b73c32b6a7c9cbaf27f` |
| サイズ | 170,519,669 bytes |
| applicationId / 版 | `com.zeroconvert.ime` / 1・1.0.0 |
| variant / ABI | debug / arm64-v8a、x86_64 |
| 証明書SHA-256 | `ed89c1107e5d4c2e543bf37a18fd63acec06e8f2482e13802c7945a9578ff3a5` |
| ソース | `feature/windows-color-themes`、HEAD `a0bd2e5` + 未コミット変更 |

`apksigner verify --print-certs` 成功。VIVOのインストール済みbase.apkに対する `sha256sum` も上記と一致した。本体とテストAPKの更新、実機7項目まで実施。正式release署名・配布、コミット・pushは未実施。既存の無関係な差分を保持している。

次の作業は、形態素メタデータを使う文節分割・境界調整・前後移動・学習の実装と、監査表に残る設定/文脈/補正・予測の差分解消。最終成果物はその変更を含めて改めて同一APKで検証する。
