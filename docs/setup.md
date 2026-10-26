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

llamaのライブラリ・CPU/CUDA/Vulkanランタイムと `zenz.gguf` はGit対象外。`windows/Makefile.toml` と `windows/.github/workflows/actions.yml` の取得・配置手順を参照し、使用するモデルを明示して配置する。後者は上流の参考ワークフローで、このリポジトリのActionsとしては実行されない。現行ZeroConモデルの公式配布URLはこの整理作業では確定していない。

`windows/deployment/` は配布用PowerShell・初期設定・NOTICEのソース。全体ビルドのpost_buildが `windows/build/` にコピーする。スクリプトは隣接するバイナリを管理するため、`deployment/` から直接インストール・起動せず、必要なバイナリと同じ配布フォルダで実行する。

既存の `ZeroConvertIME-windows/` は、このPCで利用していたローカル実行環境として場所を維持している。Gitには含めない。設定UIの開発時モデル探索は実行ファイルの祖先からリポジトリを検出する。別配置の場合は環境変数 `ZEROCON_ROOT` でモデル・ローカル配布物を置くルートを指定できる。

## Android

編集・テスト・端末更新・署名/配布の手順は [Android開発手順](android-development.md)、エージェントルールは [android/AGENTS.md](../android/AGENTS.md) を参照する。

JDK 17、Android SDK 34、NDK 27.1.12297006、CMake 3.22.1を用意する。`android/local.properties` は各PCで作成し、Gitには含めない。submoduleパッチ適用後、`android/` 内でGradleコマンドを実行する。

GGUFモデルは `android/app/src/main/assets/zenz.gguf` と `assets/models/` に別途配置する。候補と名前・期待サイズは `model/ModelManager.kt` が正本。モデルのないクローンをそのまま動作品質検証に使わない。

## Python・データ

`python scripts/run.py <リポジトリ相対の.py> [引数]` が実行ディレクトリをルートへ揃え、学習・評価用importパスを設定する。既存スクリプトの相対データ・モデルパスを保つため、この入口を使う。結果の保存先 `artifacts/results/` と `artifacts/logs/` は実行前に作成する（runnerも作成する）。

PyTorch、Transformers、Datasets、PEFT等は各学習スクリプトのimportに応じて準備する。移行前に統一された依存ロックはなく、今回も動作未確認の版固定を追加していない。データ生成の出力は `data/`、既存の学習チェックポイントは`artifacts/checkpoints/`に保持する。これらの生成物は `.gitignore` で除外する。

## 移行時の保全と公開対象

- 元のWindowsチェックアウトは `scratch/batao_src/` に保管し、今後の編集は `windows/` に限定する。
- `.local/migration-backup/windows-history.bundle` は元の全参照のGit履歴、`windows-working-tree.patch` は追跡ファイルの差分、`windows-source-manifest.json` はコピー前303ファイルのSHA-256。未追跡ソースも新しい `windows/` に取り込んだ。
- 旧チェックアウト・バックアップ・配布物・モデル・環境・ログは削除せずGitから除外した。`experiments/legacy/` の旧パスは歴史的記録として残す。実行すると旧チェックアウトを書き換えるものもあるため、通常手順として使わない。
- 公開予定ファイルは `python scripts/check_repository.py`、ステージ後は `git diff --cached --stat` と `git diff --cached --check` で確認する。
- 整理作業はコミット・pushを含めない。GitHubへの公開は、確認したステージ内容をコミットしてから行う。

## 移行時の検証結果

- 公開ソースのPython構文・通常JSON・容量・生成物除外のチェックを実施。
- 設定UIのNodeテスト18件、辞書取得後の逆引き辞書生成テスト4件が成功。
- Python runner経由の学習CLI `--help`、評価コードの正規表現、PowerShell構文、Cargoのオフラインmetadataを確認。
- 候補UIテストは8件中7件成功、1件は既存CSSと期待値の不一致。保管した旧ソースでも同じ失敗を確認。ルートCIでは手動実行の `check_candidate_ui` を有効にした場合に実行する。
- `cargo fmt --all -- --check` は既存のcompositionテストとUIコードの書式で失敗。`sync-version.mjs --check-release` も既存のTauri版情報不一致で失敗。いずれも旧ソースで再現し、今回の整理では既存の変更を保持した。
- 初回全体差分のwhitespaceチェックには取り込んだ既存ファイルの行末空白が含まれる。辞書バイナリは `.gitattributes` でbinary指定し、データ破損につながる改行変換を防ぐ。
- Windows/Androidのフルビルド、インストール、実IME入力、モデル精度評価は未実施。
