# ZeroConvert-IME

Windows版azooKeyを基盤に、zenz系のZeroCon Modelで変換操作を減らす日本語IMEを開発しています。現在はタイポ補正の安定動作と先読み精度の改善に取り組んでいます。Android実装とモデル学習・評価コードも同じリポジトリで管理します。

## 構成

| パス | 用途 |
| --- | --- |
| `windows/` | Rust/SwiftのWindows IMEとTauri設定アプリ |
| `android/` | Kotlin/JNIのAndroid IME |
| `engine/` | WindowsのSwift/Rustを直接利用するAndroid向け共通エンジン |
| `training/` | LoRA・全層学習、マージ、GGUF変換補助 |
| `benchmarks/` | 評価・解析、`research/` に研究用試作 |
| `data/` | 共有評価データと学習データ生成コード |
| `scripts/` | 依存準備、Python実行、公開対象の検証 |
| `patches/` | 外部依存への独自変更 |
| `docs/` | アーキテクチャ・ファイル地図・開発環境 |
| `experiments/legacy/` | 過去のパッチ・調査断片。通常の開発手順では実行しない |
| `reports/` | 過去の調査・実験レポート |
| `artifacts/` | ローカルのモデル・学習結果・評価結果・ログ（Git対象外） |

エージェントの入口は [AGENTS.md](AGENTS.md)。詳細は [構成](docs/architecture.md)、[探索ガイド](docs/file-map.md)、[セットアップ](docs/setup.md) を参照してください。

Windowsの機能編集からこのPCの試験IMEへの反映、インストーラー作成、Gitでの保存までの流れは [開発・反映・Gitの手順](docs/development-workflow.md) にまとめています。普段は `Build-Trial.bat` だけでビルドと適用まで行い、配布時は確認した同じ完全パッケージからインストーラーを作ります。

Androidの修正・Gradleテスト・端末へのAPK更新・署名と配布は [Android開発手順](docs/android-development.md)、エージェント向けルールは [android/AGENTS.md](android/AGENTS.md) を参照してください。APKをビルドする操作と端末へインストールする操作は別です。

Windowsのロジックを共用する新しいAndroid版は `:rebuilt`、ビルド入口は `Build-Android.bat`。
既存のキーボードを引き継ぐ構造と更新手順は [Android新構成](docs/android-shared-engine.md) を参照してください。

## 開始手順

```powershell
git clone https://github.com/g0083/ZeroConvert-IME.git
cd ZeroConvert-IME
powershell -ExecutionPolicy Bypass -File scripts/setup-dependencies.ps1
python scripts/check_repository.py
```

Windowsのビルドは `windows/` 内で、Androidは `android/` 内で実行します。モデル・辞書・ネイティブランタイムの準備は [セットアップ](docs/setup.md) を確認してください。モデル重み、配布バイナリ、学習結果、仮想環境はGitに含めません。

Pythonの実験・学習はルート基準で実行します。使用するPython環境に各スクリプトの依存を準備してください。

```powershell
python scripts/run.py training/train_nano_v2.py --help
python scripts/run.py benchmarks/research/benchmark_nano_v2.py
```

ルートのGitHub Actionsはソース検証とNodeテストを実行します。Windows/Androidのフルビルドや実IMEの動作保証を行うものではありません。

## 出典

Windows基盤: [batao9/azooKey-Windows](https://github.com/batao9/azooKey-Windows) の `23938db` を基にしたローカル変更を取り込んでいます。ライセンスは [windows/LICENSE](windows/LICENSE)、その他の同梱コンポーネントは [Windowsの表示](windows/deployment/THIRD_PARTY_LICENSES.md) と [Androidの表示](android/THIRD_PARTY_LICENSES.md) を参照してください。llama.cppの依存コミットと辞書はGit submoduleで固定します。
