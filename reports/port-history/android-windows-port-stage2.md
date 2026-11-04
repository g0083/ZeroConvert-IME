# Android移植 第2段階: モデル補正・二段先読み

続報: [第3段階の自動補正・逐次候補・再変換](android-windows-port-stage3.md)。この文書のAPK・未実装一覧は第2段階当時の記録。

2026-10-23。第1段階の実機確認は未実施のまま、モデル推論経路を追加した記録。関連: [第1段階](android-windows-port-progress.md)、[移植前75項目](windows-android-feature-gap.md)。

## 今回接続した機能

- **モデルの分離**: 通常変換の既存JNIとは別に、先読み用ZeroConと専用Correctorをそれぞれ別モデル/context/sampler/mutexでロードする。先読みは選択中の通常変換モデルと同じファイルを使い、Correctorへフォールバックしない。
- **モデル補正**: 完全一致の学習を優先し、既知の正常読みを保護する。文脈付きでCorrectorを呼び、完成した出力を規則より優先する。入力と同じ読みの完成応答は「変更不要」とし、規則で覆さない。推論が利用できない場合は既存の補正規則を利用するが、取消済み/busyの要求から古い辞書探索を続けない。
- **長文**: 入力全体を任意の文字数で切らず、128コードポイントを超える場合は通常表記と対応する句読点で末尾句を分離する。先頭の変換済み表記を保つ。要求全体は512コードポイントまで。
- **二段先読み**: 読みの続きを生成→元の読みと続き全体を表記化→現在表記で始まる場合だけ後続部分を候補化する。固定定型句は廃止し、モデルが使えない場合に定型句へ戻さない。
- **出力検査**: EOS/stopと、limit/timeout/cancelled/busy/error/unavailableを区別する。未完の追加読みは棄却し、limitでも完成した句読点区切りがある場合だけその句を使用する。表記生成が未完なら棄却する。特殊トークン、制御文字、表記の書換え、元表記の反復、文脈のない「この記事」「上記」等を検査する。
- **逆引き**: Android同梱LOUDSデータからAZR2逆引き辞書を生成し、同音・表記差を通常変換として補正から除外する。補正後のDIRECT表記から読みを復元して先読みできる。複数の辞書読みを扱い、未登録語の読みを推測して捏造しない。
- **候補の調停**: 補正と先読みを同一スナップショットに対する別workerで実行し、学習済み補正70・検査済み先読み60・一般補正30で選ぶ。弱い補正で先読みを抑止しない。今回のスコアは確率ではない。
- **補正後の先読み**: 採用済み表記の再変換を抑えた上で再要求する。この間、追加の補正は抑止し、取消状態と予測要求を分ける。
- **非同期と資源**: native要求に取消世代を渡し、decode/prefillのabort callbackでも期限・世代を確認する。モデルごとのmutexはtry-lockで、古い仕事を待ち行列へ溜めない。モデルロードを直列化し、終了処理はUIスレッド外へ移した。終了待ち中に新しい初期化・モデル変更があれば古い終了要求を破棄する。
- **キャッシュ**: 完成したモデル応答をモデル版・用途・予算・プロンプトで最大64件保持する。モデル切替後に旧版を再利用せず、終了時に消去する。本文を新規ログへ出力しない。
- **設定と評価画面**: 旧rule_based/230M/350M設定は無視し、新たに保存しない。選択中のZeroConモデルを使う説明へ更新した。補正ベンチマークもModelCorrectorへ接続し、先読みベンチマークは同じ生成・検査を呼ぶ。これらは実IMEの編集成功の検証とは別。

## 主なソース

- [assist_jni.cpp](../android/app/src/main/cpp/assist_jni.cpp)、[assist_jni.h](../android/app/src/main/cpp/assist_jni.h)、[CMakeLists.txt](../android/app/src/main/cpp/CMakeLists.txt)、[zenz_jni.cpp](../android/app/src/main/cpp/zenz_jni.cpp)
- [AssistInference.kt](../android/app/src/main/java/com/zeroconvert/ime/assist/AssistInference.kt)、[ModelCorrector.kt](../android/app/src/main/java/com/zeroconvert/ime/assist/ModelCorrector.kt)、[PredictiveAssistEngine.kt](../android/app/src/main/java/com/zeroconvert/ime/assist/PredictiveAssistEngine.kt)、[AiAssistManager.kt](../android/app/src/main/java/com/zeroconvert/ime/assist/AiAssistManager.kt)
- [ReadingCheck.kt](../android/app/src/main/java/com/zeroconvert/ime/assist/ReadingCheck.kt)、[ZenzNative.kt](../android/app/src/main/java/com/zeroconvert/ime/ZenzNative.kt)、[ModelManager.kt](../android/app/src/main/java/com/zeroconvert/ime/model/ModelManager.kt)
- [入力サービス](../android/app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt)、[設定画面](../android/app/src/main/java/com/zeroconvert/ime/MainActivity.kt)、[BenchmarkRunner.kt](../android/app/src/main/java/com/zeroconvert/ime/benchmark/BenchmarkRunner.kt)
- [資産準備](../scripts/prepare-android-assist-assets.ps1)、[Gradleの資産検証](../android/app/build.gradle.kts)、[ModelAssistTest.kt](../android/app/src/test/java/com/zeroconvert/ime/ModelAssistTest.kt)

## 準備・ビルド・検証

初回はルートで実行する。元の重みは書き換えず、カタログ一致を検証してAndroid assetsへコピーする。逆引きはWindows実行フォルダのバイナリをコピーせず、Androidの辞書ソースから既存の生成器で作成する。

```powershell
.\scripts\prepare-android-assist-assets.ps1
```

Android assets内のGGUFと生成済みreverse-v2.binは引き続きGit対象外。ビルドは不足・CorrectorのSHA不一致・逆引きの形式不一致で停止する。Correctorは起動時のアプリ内展開でもSHAを検証し、通常変換モデルの選択一覧とは別ディレクトリで管理する。

android/で実行:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline
```

結果: **成功**。JVMテスト49件、失敗0、エラー0、スキップ0。lintエラー0・警告28。arm64-v8a/x86_64の新JNIを含むdebug APK作成成功。テストには実生成辞書の読取・同音判定・複合語の読み復元も含む。

JVMのモデル応答テストは注入した応答を使う。実端末のJNIを実行してモデル品質・表示遅延・キャンセル性能を計測したものではない。

## APK・モデル情報

| 項目 | 値 |
|---|---|
| APK | `android/app/build/outputs/apk/debug/app-debug.apk` |
| applicationId | `com.zeroconvert.ime` |
| variant / 版 | debug / versionCode=1、versionName=1.0.0 |
| ABI | arm64-v8a、x86_64（APK内容を確認） |
| サイズ | 170,495,739 bytes |
| APK SHA-256 | `5335b26b22248c2777e244f48c951733139bff51cba94efe23bedcc81fcfd084` |
| 署名 | apksigner verify成功、Android Debug、v2署名 |
| ソース | HEAD `a0bd2e5`＋既存未コミット変更＋第1/第2段階の未コミット変更 |

以下は**APK内の実ファイル**を読み取ったSHA-256。

| ファイル | bytes | SHA-256 |
|---|---:|---|
| models/zerocon-model-corrector.gguf | 18,452,384 | `0641a5be4e7d9f563776275f9904b5cad9875e84e752d146949ead3c5ac7089a` |
| models/zenz-v3.2-xsmall-q4km.gguf | 18,452,288 | `e0d2d20ff3da5dc71ba8e0fdc14c70701aec01b52afe75eba8e1f2ec0780b26e` |
| models/zenz-v3.2-small-q4km.gguf | 64,282,688 | `94c290aa772ae9f66bca45842dd506dbedeba5ef6f67368189261ff7364f25f2` |
| zenz.gguf (Nano Lite) | 16,078,208 | `25ee61bebdc7ada57d52a5b0d428eaeedc77df3b1ffa41fae291cc5cd8e752b2` |
| dictionary/reverse-v2.bin | 19,315,171 | `0dc52727c4e403184dcdd365c2817ddd246369a1ea9b3025ae13c5b5bca40621` |

Corrector・Nano Q4・Standard Q4はWindowsカタログと一致。通常変換モデルの既定は従来どおりNano Q4で、既存の選択・取込モデルは上書きしない。

## Android用の予算と未確認点

- 各アシストモデルはCPU2スレッド、context1024 token、prefill batch128。通常変換の既存contextは別。先読みは同じ重みを追加ロードするため、メモリ増加を実機で確認する必要がある。
- Correctorは最大384 token/1200ms、先読みの読み延長24 token/800ms、全体表記化は最大256 token/1000ms。Windowsの時間値のコピーではない。入力・生成予算がcontext容量を超える要求は、任意切詰めせず失敗させる。
- 期限・取消はnative batchの途中でもcallbackで確認するが、端末上のキャンセル応答時間は未計測。
- 現在のManagerは独立要求の両方が終わってから1件を返す。Windows同様の途中結果提示・表示済み採用先の固定、根拠付き自動適用は未移植。
- Swift固有のラティス内タイポ補正・N-best統合・文節操作・通常変換学習、語彙/規則の全データ同期、自動確定履歴の完全同等化、選択文字列の再変換、外部文脈の応答時再取得は残る。
- 本PCでの端末インストール、InputConnection経由の実IME確認、正常入力の誤補正・取りこぼし・先読み妥当性・遅延・RAM/発熱の比較は未実施。実機確認なしに移植全体の品質がWindows相当になったとは扱わない。

Windowsソース・外部依存・元のモデル重みは編集していない。作業前の主要ファイルは `.local/android-port-stage2-before-20261023/` に保存。端末更新・正式release署名・配布・Windows適用・インストーラー作成・commit/pushは実施していない。
