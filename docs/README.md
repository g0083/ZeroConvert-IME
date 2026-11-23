# ドキュメント索引 (`docs/`)

`docs/` には、リポジトリ全体の現行仕様・設計・運用手順・開発ガイドを配置します。ドキュメントの作成・配置・更新の基準は [documentation-guide.md](documentation-guide.md) を参照してください。

## 全体構成・探索・ルール

| ファイル | 内容 |
| --- | --- |
| [architecture.md](architecture.md) | 全体の技術スタック、データフロー、ポート・設定・ビルドコマンド |
| [file-map.md](file-map.md) | 症状別の探索先、各ディレクトリと主要ファイルの責務 |
| [documentation-guide.md](documentation-guide.md) | ドキュメントの作成・配置・ライフサイクル管理ルール |
| [setup.md](setup.md) | 初回セットアップ、submodule、依存準備、実行環境 |

## Windows 開発・配布・機能仕様

| ファイル | 内容 |
| --- | --- |
| [development-workflow.md](development-workflow.md) | ソース編集から試験 IME 反映（`Build-Trial.bat`）・インストーラー作成・Git 保全までの手順 |
| [local-installer.md](local-installer.md) | 共通配布パッケージ、試験環境適用、Inno Setup インストーラー作成の詳細 |
| [windows-conversion-logic.md](windows-conversion-logic.md) | Windows 版の入力 → 変換 → 表示 → 確定・アシストの層別解説 |
| [typo-correction.md](typo-correction.md) | Windows タイポ補正（`keyboard-prior` + メインモデル統合 Stage-1/2）の仕様・検証 |
| [predictive-assist.md](predictive-assist.md) | Windows 文の先読み（ZeroCon 2段生成）の仕様・検証 |
| [english-completion.md](english-completion.md) | 英字モードの単語補完と Windows/Android 共有学習の仕様 |
| [model-assets.md](model-assets.md) | ZeroCon Model（Nano / Standard v4.2 4種・同梱せず取得）・バックエンド・翻訳モデル配置 |
| [dictionary-assets.md](dictionary-assets.md) | 日本語辞書（azooKey標準辞書＋追加辞書）の実行時取得・配置・初回起動ゲート |

## Android・共有エンジン開発

| ファイル | 内容 |
| --- | --- |
| [android-development.md](android-development.md) | Android 版の修正・テスト・端末反映・配布手順の正本 |
| [android-shared-engine.md](android-shared-engine.md) | `:rebuilt` と Windows 共有 Swift/Rust エンジンの構成・ビルド・検証 |
| [android-conversion-logic.md](android-conversion-logic.md) | Android 版の入力 → 変換 → 表示 → 確定の層別解説 |
| [android-performance-size.md](android-performance-size.md) | 共通エンジン版の APK 容量・推論性能改善の記録（2.0.5-dev） |

## モデル学習・データ分離・評価契約

| ファイル | 内容 |
| --- | --- |
| [dataset-isolation.md](dataset-isolation.md) | 学習・検証・評価データのリーク分離手順と検査 |
| [zenz-v32-hf-extraction.md](zenz-v32-hf-extraction.md) | `zenz-v3.2` GGUF（Apache-2.0）からの Hugging Face / PyTorch 重み逆展開手順 |
| [corrector-evaluation.md](corrector-evaluation.md) | 補正モデル評価契約（研究・ベンチマーク指標の定義） |
| [qwerty-ranking-corrector.md](qwerty-ranking-corrector.md) | Standard v4 QWERTY 候補順位付けの研究手順 |
| [v4-unified-training.md](v4-unified-training.md) | v4 派生・Standard-QWERTY 3タスク統合モデルの研究学習手順 |
| [origin-training.md](origin-training.md) | ZeroCon-Origin（ランダム初期化・タグ統合）の研究学習手順 |

## 関連ディレクトリ

- [docs/investigations/README.md](investigations/README.md): 個別機能の設計メモ、実装計画（実施状況つき）、バージョン別不具合解析
- [reports/README.md](../reports/README.md): モデル開発、タイポ/先読み検証、Android 移植履歴、Web 比較の各種レポート
