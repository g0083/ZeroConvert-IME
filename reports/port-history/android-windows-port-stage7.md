# Windowsロジック移植 第7段階: 範囲の訂正・文脈・端末比較

2026-10-24。専用の範囲変換・文節変換・全文確定UIはWindowsのロジック移植を超えた独自追加だったため取り下げた。既存の部分確定とその不具合修正は保持する。新たな文節操作UIは残作業に含めない。

## Windowsを根拠に反映した処理

| 対象 | Windowsの根拠 | Androidでの反映と証拠 |
|---|---|---|
| 文頭・文末の辞書接続 | Swift依存の `CIDData` はBOS=0、EOS=1316。`MIDData` は双方500 | `azookey_louds.cpp` の1316/1317を訂正。arm64-v8a/x86_64ビルド、端末native辞書テスト成功。候補順位全般の一致を証明したものではない |
| 補正学習の検索・保持 | `ai_assist/learning.rs` の完全一致優先、3文字以上の最長末尾一致、更新時刻順 | `TypoLearningHistory.lookupRule` と時刻の永続化。自動適用の根拠は完全一致のまま。`LearningParityTest` 4件成功 |
| 学習モード | Swift `learningType(for:)` のread/write、read only、disabled | `LearningPolicy` と通常選択・補正採用・検索の制御。専用設定UIは追加していない。設定UIと通常変換器の学習方式の全面互換は未完 |
| 文脈の上限・再確認 | Correctorの左文脈512文字、predictionの256文字、client `apply_typo_assist_result` の周辺文再取得 | サービスの40文字キャッシュを最大512コードポイントへ拡張。実際に設定したcomposing previewを区別し、応答表示・補正採用・確定時に確認済み左文脈を再取得して照合。候補処理はworkerで継続。`EditorContextTest` 3件と端末上の同位置での文書変更検出が成功 |
| 補正専用キャッシュ | Correctorの32件キャッシュ | JNI完了応答キャッシュをCorrector 32件とprediction 64件へ分離。モデル版・種類・プロンプト・トークン上限をキーにし、モデル終了時に消去 |

## ビルドと端末検証

実行したコマンド:

```powershell
# android/内
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline
```

成功。JVM97件、失敗・エラー0。lintは0 errors / 28 warnings。テストrunnerの報告を改善した後は `assembleDebugAndroidTest` も成功した。

利用者が接続先を変更し、その端末での検証を指定したため、現在の対象は **OPD2415 / API36 / arm64-v8a**。本体・テストAPKを同一署名で更新し、インストール済みbase.apkのSHA-256も照合した。既定IMEは変更せず、アンインストール・データ消去は行っていない。

基本動作8項目は成功: テスト用入力欄、部分確定後の末尾保持、実InputConnectionでの巻戻し、モデル資産読込、カーソルを動かさない前文変更の検出、native形態素情報、逆引き辞書と機密欄判定、実IMEの5打鍵と確定。

runnerは各項目の開始・結果を逐次表示し、画面起動待ち10秒、UI要求待ち5秒を上限とした。合成入力による学習はプロセス内だけで抑止し、設定ファイルを書き換えない。端末の通常学習ファイルのSHA-256は前後で不変だった。

## 精度測定で判明した未解決点

`am instrument -w -r -e quality true ...PortInstrumentationRunner` で既存の評価データも実行した。

| 対象 | 結果 | 平均処理時間 |
|---|---|---|
| 補正の既存35例 | 期待値・許容値一致9/35 | 約27.9ms |
| 正常入力8例 | 不要な補正提案2/8。どちらも自動適用の根拠なし | 個別記録あり |
| 先読みの既存30例 | 期待値・許容値一致8/30 | 約444.2ms |

先読みの機械的な一致率は、自然な別表現も不一致に含む。値はモデル・設定・このデータに対する測定であり、実際のキー停止から表示までの遅延や総合的な日本語品質を表さない。

正常入力は「とうきょう→共有」「かんじへんかん→環境変数」。実行中のWindows Correctorサーバーへ同じプロンプトを送り、同じ2出力を再現した。`/props` のモデルパスのファイルSHA-256はAndroid同梱Correctorと一致した。誤入力データの先頭8例もWindowsとAndroidの出力が全件一致し、期待値から外れた出力も一致した。したがって少なくともこれらはAndroidだけの生成差ではない。Windows側の入力判定・クライアント表示を含む全経路の同一性を証明したわけではない。

品質検査を含むrunnerは **11項目中、正常入力の検査1項目が失敗**。精度問題を隠すために期待値や成功条件は変更していない。モデル共通の課題と、通常変換エンジン・候補統合の残る差分を区別し、移植全体は未完としている。

## 検証したAPK

| 項目 | 値 |
|---|---|
| 固定保存先 | `.local/android-trials/20261024-context/app-debug.apk` |
| SHA-256 | `7c01534882501377de2e3a8e35b0230c1a5823d01f4655f66ac8f73f28bd1c8c` |
| サイズ | 170,519,669 bytes |
| applicationId / 版 | `com.zeroconvert.ime` / 1・1.0.0 |
| variant / ABI | debug / arm64-v8a、x86_64 |
| 証明書SHA-256 | `ed89c1107e5d4c2e543bf37a18fd63acec06e8f2482e13802c7945a9578ff3a5` |
| Corrector SHA-256 | `0641a5be4e7d9f563776275f9904b5cad9875e84e752d146949ead3c5ac7089a` |
| 通常変換・先読み | 利用者が選択済みのZeroCon Standard（変更せず）。端末内ファイルSHA-256: `94c290aa772ae9f66bca45842dd506dbedeba5ef6f67368189261ff7364f25f2` |
| ソース | `feature/windows-color-themes` / HEAD `a0bd2e5` + 未コミット変更 |

同じ保存先の `instrumentation-quality-final.txt`、`port-quality-final.jsonl`、`windows-model-comparison.json` に結果を保持した。正式release署名・配布・commit/pushは未実施。

## VIVOで中断したテストの後処理

旧runnerは `startActivitySync` で待機していた。JDWPで停止箇所を確認し、端末側の凍結も確認した。その後、利用者がUSB接続先を変更したため、ホスト側の待機は切断で終了し、旧runnerのfinallyによる設定復元は確認できていない。旧runnerが一時保存した学習モードREAD_ONLYの復元はVIVO再接続時の確認が残る。元値を推測して書き換えず、実行中スタックの `previousMode` を確認する。現runnerではこの永続設定変更を廃止した。
