# 開発環境と初回公開の準備

## Gitと依存コード

開発ソースはルートのGitで一括管理する。`windows/` は通常ディレクトリであり、別リポジトリではない。外部依存だけをルートの `.gitmodules` で固定する。

| 依存 | 固定コミット |
| --- | --- |
| Windows変換辞書 | `b05798b43679c385ce2179fb746512d7643dfe12` |
| Windows絵文字辞書 | `24e88dd14c27e333454ba15c7cc4c2b4a4dd9d34` |
| Android llama.cpp | `8115b4c31faeab22954383202e0c26c4e55944e4` |

辞書の正確なpinはGitのgitlinkが正本。更新時は上表も合わせる。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/setup-dependencies.ps1
```

このスクリプトはsubmoduleを初期化し、`patches/llama.cpp/zerocon-compat.patch` を適用する。パッチは変換器のモデル識別・出力重みの書き出しと `llama-vocab.cpp` のGPT-2事前トークナイザー登録を含む。適用済みなら再適用しない。submoduleの `ignore = dirty` はこのローカルパッチ・ビルド出力をルートの通常statusから除くための指定。依存変更を調べる際は `git -C android/app/src/main/cpp/llama.cpp diff` で直接確認する。

## Windows

ビルド要件とコマンドは [architecture.md](architecture.md) に集約する。コマンドは `windows/` で実行する。辞書は上記submodule、Swift変換依存は `server-swift/Package.swift` が取得する。

llamaのライブラリ・CPU/Vulkanランタイムと変換ライブラリは `windows/scripts/prepare-modern-llama.py`（`Initialize-ModernLlama`）が管理し、`windows/deployment/model-catalog.json` に定義された ZeroCon Model（Nano v4.1 `IQ4_emb8` / `Q8`）は `artifacts/models/zerocon/` から `windows/scripts/sync-distribution-models.ps1` で配置する（CUDAは廃止済み）。モデル重みやランタイムバイナリはGit対象外。

`windows/deployment/` は配布用PowerShell・初期設定・NOTICEのソース。パッケージ作成時に配布先へコピーされる。スクリプトは隣接するバイナリを管理するため、`deployment/` から直接インストール・起動せず、必要なバイナリと同じ配布フォルダで実行する。

既存の `ZeroConvertIME-windows/` は、このPCで利用していたローカル実行環境として場所を維持している。Gitには含めない。設定UIの開発時モデル探索は実行ファイルの祖先からリポジトリを検出する。別配置の場合は環境変数 `ZEROCON_ROOT` でモデル・ローカル配布物を置くルートを指定できる。

## Android

編集・テスト・端末更新・署名/配布の手順は [Android開発手順](android-development.md)、エージェントルールは [android/AGENTS.md](../android/AGENTS.md) を参照する。

JDK 17、Android SDK 34、NDK 27.1.12297006、CMake 3.22.1を用意する。`android/local.properties` は各PCで作成し、Gitには含めない。submoduleパッチ適用後、`android/` 内でGradleコマンドを実行する。Gradleモジュールは `:rebuilt` だけで、版・SDK・NDKは `android/gradle.properties` が正本。

AndroidのSwift依存は `engine/Package.resolved` から解決するため、WindowsのcheckoutやSwift toolchainを用意しなくても共有エンジンをステージングできる。Swift変換ライブラリは `node scripts/prepare-shared-engine.mjs` がWindowsと同じvendored converterを用意する。手順と検査コマンドは [Android新構成](android-shared-engine.md) を参照する。

GGUFモデルは `scripts/prepare-android-conversion-models.ps1` で検証する（`Nano / Standard v4.2 IQ4_emb8` / `Q8`、APK には同梱しない）。候補と名前・期待サイズは `model/ModelManager.kt` が正本。モデルのないクローンをそのまま動作品質検証に使わない。

## Web（ブラウザ体験版）

Web版（Wasm / Web Worker）の構成とテスト実行手順は [web/README.md](../web/README.md) と [docs/investigations/browser-ime.md](investigations/browser-ime.md) を参照する。

## Python・データ

`python scripts/run.py <リポジトリ相対の.py> [引数]` が実行ディレクトリをルートへ揃え、学習・評価用importパスを設定する。既存スクリプトの相対データ・モデルパスを保つため、この入口を使う。結果の保存先 `artifacts/results/` と `artifacts/logs/` は実行前に作成する（runnerも作成する）。

PyTorch、Transformers、Datasets、PEFT等は各学習スクリプトのimportに応じて準備する。データ生成の出力は `data/`、既存の学習チェックポイントは`artifacts/checkpoints/`に保持する。これらの生成物は `.gitignore` で除外する。

## ソース整合確認と旧移行メモ

- ソース検証は `python scripts/check_repository.py`、差分確認は `git diff --cached --stat` と `git diff --cached --check` で行う。
- 2026-10-22 の初期リポジトリ統合時の保全物（`scratch/batao_src/`、`.local/migration-backup/`、`experiments/legacy/`）は歴史的保管物であり、通常の開発・編集対象ではない。当時の復旧記録は [windows-source-recovery.md](../reports/port-history/windows-source-recovery.md) を参照。
