# 共通エンジンAndroid版の検証記録

2026-10-24。WindowsのSwift/Rustを直接利用する `:rebuilt` の初回実装。
従来のKotlin/C++移植版とは別の成果物。構造・更新手順は [新構成](android-shared-engine.md)。

## 実装した範囲

- Windows Swiftの通常変換、N-best統合、辞書補正、候補IDによる学習、再変換の読み復元。
- 同じRustソースによる補正・先読み・動的マクロ。ホスト差は推論コールバックとファイル配置に限定。
- Windows/Android共用の自動確定境界・安定性判定。
- 原打鍵/入力方式/separator、Unicode、候補が消費した入力境界の橋渡し。
- 既存のフリック/QWERTY、キーボード/クリップボード/テーマ/設定画面のソース共用。
- Windows依存pin・辞書・ソースとnative指紋の照合、古い生成ソース/辞書ファイルの除去。
- `Build-Android.bat` による2 ABIビルド・テスト・lint・APK作成。端末更新は別操作。

## 検査

| 検査 | コマンド・結果 |
|---|---|
| 共通Rust | `cargo test --locked --manifest-path engine/rust/Cargo.toml --lib -- --test-threads=1`、52成功・実モデル接続用1除外 |
| Windowsクライアント/共通設定 | `cargo test -p shared -p azookey-windows --lib`、374/35成功、除外2/1。最終変更後の自動確定2件も成功 |
| Windowsサーバー | `cargo check -p azookey-server --bins` 成功 |
| Windows Swiftキャッシュ | `swift test --package-path windows/server-swift --build-system native --skip-build -c release --filter LearningCandidateCache`、2成功 |
| 従来Android JVM | `:app:testDebugUnitTest`、124成功 |
| 新構成Android JVM | `:rebuilt:testDebugUnitTest`、17成功 |
| lint | `:rebuilt:lintDebug`、0 errors / 28 warnings |
| APK | `:rebuilt:assembleDebug :rebuilt:assembleDebugAndroidTest` 成功 |
| native | arm64-v8a/x86_64、各27ライブラリのELF ABI・SONAME・依存閉包、正本とバイナリの指紋照合成功 |
| 実機基本 | 指定されたOPD2415 / Android16 / API36 / arm64で18件・失敗0 |

Swiftテストの初回実行はXCTest/Testing/llamaのDLL検索パス不足で起動できず、
既存DLLのPATHを設定して上記2件を実行した。Windowsの試験IMEへは適用していない。
`sync-version.mjs --check-release` は既存Tauri版メタデータの不一致を報告した。
このAndroid変更ではWindowsの版情報やインストーラーを変更しない。

## 実機確認と修正

同じアプリIDと互換なdebug証明書で更新した。アンインストール、データ消去、既定IME変更は行っていない。
最終の端末内base.apkと作成したAPKのSHA-256一致を確認した。
設定画面を実際に開き、設定・キーボード・モデル項目の表示も確認した。

初回の実機検査でSwift C入口のmain-queue assertionによる停止が判明した。
Windowsの注釈をAndroid専用Swift actorへ解決し、JNIワーカーからTaskへ渡す形に修正した。
テスト資産の梱包漏れ、候補の学習可否メタデータ、消費した入力境界も修正して再検証した。
これらの失敗APKは最終成果物と区別する。

基本検査には実InputConnection、キーの実タップと確定、部分確定後の末尾保持、巻戻し、
未確定ローマ字、Swift変換、カーソル前変換、絵文字、再変換、native辞書/モデル、
104辞書補正エントリ、ユーザー語のキャッシュ分離、旧形態素履歴の破損保護を含む。
合成入力の学習保存はプロセス内で抑止した。通常利用の全アプリでの確認を代替するものではない。

## 精度と遅延

通常変換・先読みの選択モデルはZeroCon Nano Q4_K_M。
SHA-256は `e0d2d20ff3da5dc71ba8e0fdc14c70701aec01b52afe75eba8e1f2ec0780b26e`。
Correctorは `0641a5be4e7d9f563776275f9904b5cad9875e84e752d146949ead3c5ac7089a`。

| 対象 | 結果 | 平均処理時間 |
|---|---|---|
| 補正35例 | 期待値/許容値一致9/35 | 約27.6ms |
| 正常入力8例 | 不要な補正提案3/8 | 個別記録あり |
| 先読み30例 | 期待値/許容値一致12/30 | 約106.9ms |

正常入力の不合格は「きょうと→共有」「とうきょう→共有」「かんじへんかん→環境変数」。
モデル・規則の品質課題が残るため、精度検査を含む実行は21件中1件不合格と記録する。
基本18件の成功と混同しない。

過去の第7段階はStandardを選択しており、補正9/35・先読み8/30・不要提案2/8だった。
今回とは先読みモデルが異なるため、先読みの一致数や遅延を移植だけによる改善とは主張しない。
上記の時間はベンチマーク内部の処理時間で、打鍵停止から画面表示までの遅延ではない。

## 残る確認

- 品質の不合格3例と、広い正常入力/タイポ/先読みデータでの比較。
- 同一モデルでのWindowsとの全出力比較と、日常利用アプリでの編集/フォーカス/モデル切替の網羅確認。
- Android独自の形態素頻度バイナリからSwift学習形式への移行。元ファイルは保持する。
- 正式release署名・配布。現在は開発用debug APK。

Gitコミット/pushは行っていない。既存の未コミット変更を含む作業ツリーを保持した。

最終APKは `2.0.0-dev` / versionCode 2 / `com.zeroconvert.ime`、debug署名。
SHA-256: `5f05407c5c4019f7db0d9fc2032f9f6d7fe2a3647ebd1776e5055a1927e01d61`。
この同じAPKをOPD2415で検査した。配布候補と詳細記録は `dist/android/` に固定保存する。
