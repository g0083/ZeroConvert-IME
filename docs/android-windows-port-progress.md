# WindowsロジックのAndroid移植: 第1段階

最新: [第10段階の形態素の頻度学習](android-windows-port-stage10.md)。JVM120件、基本動作17項目が成功。単語/文節bigram/全文の学習、減衰・失効、保存とnative学習語を接続した。AI候補との対応付け、自動確定の学習、精度等の残件があり全体は未完。全体の残件は [75項目の完了監査](android-port-completion-audit.md) で管理する。

続報: [第2段階のモデル補正・二段先読み](android-windows-port-stage2.md) を実装済み。この文書のAPK・未実装一覧は第1段階当時の記録。

2026-10-23。[75項目の移植前比較](windows-android-feature-gap.md) に基づく実装記録。

## 今回の実装

モデル補正・二段先読みの前提となる、日本語入力の状態管理と補正採用を先に実装した。75項目全体の移植完了ではない。

| 項目 | 今回の変更 |
|---|---|
| 補正の内部反映 | `CorrectionSession` がComposingTextの打鍵・読み・カーソルを保存し、採用した表記をDIRECT入力として置換。カーソル後の文字列も保持する。 |
| 取消 | Backspaceで内部打鍵・カーソル・元の変換表示・候補一覧を復元する。取り消した表記を同じ状態で再提示しない。次の通常編集で拒否状態を解除する。 |
| 追加入力 | 補正後の状態から入力を継続。明示採用した接頭部は再変換せず、新しく入力した末尾だけを変換する。かな表記の補正も同じ扱い。 |
| 確定・学習 | タップ時には学習せず、InputConnectionのcommitText成功後にのみ記録。取消・入力先終了・失敗した確定は採用回数に加えない。保存はIOスレッドで実施。 |
| 採用回数 | 同じ読みでも表記が変われば1回に戻す。最大500件を維持し、最近採用された項目を残す。既存のSharedPreferencesキー・JSON形式は維持する。 |
| 古い結果の棄却 | 要求世代・原入力要素・全文/カーソル・左文脈キャッシュ・プロフィール・設定・モデル版・入力先・EditorInfoを照合。削除再入力で同じ読みに戻っても以前の結果を採用しない。確定用の再推論にも適用する。 |
| モデル切替 | モデルロードの前後・終了時に版を進め、切替前の推論結果を反映・タップ採用しない。 |
| 機密欄 | パスワード各種・NO_PERSONALIZED_LEARNINGではAI変換/アシスト・個人辞書/学習候補利用・学習記録を抑止。英語の確定学習も成功後の共通経路へ合わせる。 |
| ローマ字復元 | 平仮名化関数の誤用を除去し、既存ローマ字入力表でかなへ戻す。「ん」の明示境界にも対応。`おｋせり`、`おｔかれ`、`あｒがとう`、`こｎにちは`を回帰検証。 |
| 正常読みの保護 | 学習完全一致を優先した後、既知の正常語を距離検索から除外する。 |
| 処理上限 | 補正対象を2〜128 UTF-16単位に制限し、native辞書による補正探索は最大32変種に制限。端末上の取りこぼし・遅延比較は未実施。 |
| 自動確定 | 確定読み8・表記4文字の下限と、平仮名の後ろの句読点だけでは切らない条件をWindowsに合わせる。補正直後と最初の追加入力では確定を抑止し、その後の編集で安定履歴を作り直す。確定失敗時は内部入力を削らない。 |
| API互換性 | lintで見つかった既存のAPI29用設定画面テーマに、API26〜28用のfallbackを追加。API29以降のDayNightは維持。 |

主な変更元:

- [入力サービス](../android/app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt)
- [CorrectionSession](../android/app/src/main/java/com/zeroconvert/ime/assist/CorrectionSession.kt)、[AssistRequestGate](../android/app/src/main/java/com/zeroconvert/ime/assist/AssistRequestGate.kt)、[InputPrivacy](../android/app/src/main/java/com/zeroconvert/ime/assist/InputPrivacy.kt)
- [変換器](../android/app/src/main/java/com/zeroconvert/ime/KanaKanjiConverter.kt)、[ComposingText](../android/app/src/main/java/com/zeroconvert/ime/azookey/input/ComposingText.kt)、[ZenzNative](../android/app/src/main/java/com/zeroconvert/ime/ZenzNative.kt)
- [ローマ字復元](../android/app/src/main/java/com/zeroconvert/ime/assist/RomajiDecoder.kt)、[補正](../android/app/src/main/java/com/zeroconvert/ime/assist/TypoCorrectionEngine.kt)、[補正学習](../android/app/src/main/java/com/zeroconvert/ime/assist/TypoLearning.kt)
- [Manifest](../android/app/src/main/AndroidManifest.xml)、[基本テーマ](../android/app/src/main/res/values/styles.xml)、[API29以降のテーマ](../android/app/src/main/res/values-v29/styles.xml)

## 検証

android/で実行:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline
```

- JVMテスト: 34件、失敗0、エラー0、スキップ0。
- lint: エラー0、警告28。警告は残しており、lint抑制やbaselineによる隠蔽はしていない。
- debug APK: ビルド成功。ABIはarm64-v8aとx86_64。
- 回帰テストは [CorrectionSessionTest](../android/app/src/test/java/com/zeroconvert/ime/CorrectionSessionTest.kt)、[AiAssistTest](../android/app/src/test/java/com/zeroconvert/ime/AiAssistTest.kt)、[AutoCommitAndPipelineTest](../android/app/src/test/java/com/zeroconvert/ime/AutoCommitAndPipelineTest.kt) を追加・更新した。
- JVMは実InputConnection・JNIモデル推論を検証していない。エディターが成功を返した場合の判定を実装したが、端末上の確定成功まで確認した意味ではない。
- 初回のoffline lintはlint-gradle未取得で失敗した。通常モードで必要な依存を取得後、上記offline検証を実施した。

最終APK:

| 項目 | 値 |
|---|---|
| パス | `android/app/build/outputs/apk/debug/app-debug.apk` |
| applicationId | `com.zeroconvert.ime` |
| variant / 版 | debug / versionCode=1、versionName=1.0.0 |
| ABI | arm64-v8a、x86_64（APK内の.so配置を確認） |
| サイズ | 140,451,029 bytes |
| SHA-256 | `4afaf3eb5b4ca3b081d62ef9f782ce137a920c4f16cacecc2307ec0f0aef4c2d` |
| 署名検証 | build-tools 34.0.0のapksignerで検証成功、APK Signature Scheme v2、Android Debug署名 |
| 証明書SHA-256 | `ed89c1107e5d4c2e543bf37a18fd63acec06e8f2482e13802c7945a9578ff3a5` |
| ソース | HEAD `a0bd2e5`＋既存の未コミット変更＋今回の未コミット変更 |

APKビルド後、最終の自動確定連携変更に合わせて全3タスクを再実行し成功した。同じ最終APKのSHA-256と署名を再確認した。上記の値はこの成果物の記録であり、次のビルド出力を同一成果物として扱わない。

## 残る移植作業

1. 専用Correctorのモデル配置・ロード・独立推論資源、終了理由/予算を返すJNI API。
2. ZeroConによる読み延長→全体変換の二段先読みと、現在表記を変更しない出力検査。現在の定型句先読みと旧モデル選択設定はまだ置き換えていない。
3. 補正/先読みの独立生成、根拠による優先順位・表示安定性・自動適用。
4. 逆引き辞書による同音判定と漢字→読み復元。選択文字列の再変換も未実装。
5. Windowsの辞書補正・N-best統合・文節境界・通常変換学習への適応。自動確定の履歴方式とraw境界判定も、今回の下限変更だけで同等とは扱わない。
6. エディター外部の文脈変更を応答時に再取得して照合する処理。今回は保存済みの左文脈との照合まで。
7. 端末の実IMEで打鍵/フリック→採用→追加入力→削除→カーソル/選択→確定/取消→入力先/モデル切替を確認。正常入力の誤補正・取りこぼし・先読み妥当性・遅延の比較。

## ソース保全・反映範囲

作業開始時からAndroidや文書に未コミット変更が存在した。共有ブランチを切り替えず、それらを保持して編集した。既存変更のある主要ファイルの作業前コピーは `.local/android-port-before-20261023/` に置いた。これはGitコミットの代わりではない。

Windowsソース・モデル重み・外部依存は変更していない。APKは現在の作業ツリー全体（以前からのAndroid変更も含む）から作成したdebug開発用。端末更新・実IME操作・release署名・配布・Windowsへの適用・インストーラー作成・commit/pushは実施していない。
