# ZeroConモデルとバックエンドの配置

通常変換・先読みの選択肢はWindows・Android共通で次の2種類。新規導入はIQ4_emb8を既定とする。

| 設定ID | 配布ファイル名 | 容量 |
|---|---|---:|
| `nano_v4_1_iq4_emb8` | `zerocon-nano-v4.1-iq4nl-emb8.gguf` | 19,602,048 bytes |
| `nano_v4_1_q8` | `zerocon-nano-v4.1-q8_0.gguf` | 29,039,232 bytes |

IQ4_emb8はNano v4.1の省メモリ版（内部IQ4_NL、入力埋め込み・出力Q8_0）。Q8はQ8_0高精度版。どちらもzenz-v3.2-xsmall（Apache-2.0）に追加学習した独自改変モデルで、上流のApache-2.0条件は同梱表示で満たすが、重みの再利用・再配布は許諾しない。SHA-256・元ファイル名・モデル仕様の正本は `windows/deployment/model-catalog.json` とする。

旧設定・旧ファイルとの互換移行にのみ対応し、旧 `zenz.gguf` 等をそのまま現行Nanoとしてフォールバック読み込みはしない。モデル未配置なら新しい配布資産を準備する。

## 配置と移行

Windowsの正本は `artifacts/models/zerocon/`、インストール先は `{app}/models/`。`windows/scripts/sync-distribution-models.ps1` がハッシュ一致する生成済みモデルを集約し、`Build-Package.bat`も同じ処理を利用する。選択は `zenzai.model` へ保存し、サーバー再起動で反映する。

旧 `nano_v4_q8` / `standard` / `standard_q4km` はNano v4.1 Q8へ、旧 `nano_v4_iq4_emb8` / `nano` / `nano_q4km` はNano v4.1 IQ4_emb8へ読み替える。旧重みや `zenz.gguf` を新しいNano v4.1として扱うフォールバックはしない。モデル未配置なら新しい配布資産を準備する。

Androidは `scripts/prepare-android-conversion-models.ps1` でハッシュ検証後に `android/app/src/main/assets/models/` へコピーする。`:rebuilt` が同じ2種類を梱包し、Gradleの `verifyConversionModels` でサイズ・ハッシュを検査する。旧assetsはローカルに残してAPKの同期対象から除外する。既存の管理下Nano v4 / Standard選択はQ8/IQ4_emb8へ移行する。ユーザーがインポートした別パスのモデルや補正履歴は保持する。

モデル重みはGit対象外。clone後はカタログと一致する重みを別途用意する。コピー・ビルド・実IMEへの適用・端末更新は別操作である。

## バックエンド

- **対応バックエンド**: `vulkan`（既定）および `cpu` の2種類。Windows 配布パッケージには `llama_vulkan/` と `llama_cpu/` を同梱する（依存準備は `windows/scripts/prepare-modern-llama.py` が llama.cpp `7d516e2` / b12125 を取得・適用）。
- **廃止済みバックエンド**: CUDA（`llama_cuda` / `cuda_backend.rs`）および LFM（`llama_lfm`）は廃止された。旧設定の `"cuda"` は読み込み時に `vulkan` へ移行／フォールバックする。更新インストーラーは旧 `llama_cuda` / `llama_lfm` や旧名モデルをインストール先から削除する。
- **ワーカーとの共有**: 変換サーバー（18091番）、`keyboard-prior.exe`、翻訳サーバー（18092番）、設定画面のベンチマーク（8090番）はすべて共通の `shared::runtime_assets` を参照する。

## 翻訳モデル（PLaMo 3 Translate）

- 翻訳モデルは Windows インストーラー・Android APK のいずれにも同梱しない。設定画面で翻訳機能を有効化した際、ユーザーの同意のもとで Hugging Face のピン留めリビジョンからオンデマンドダウンロードし、SHA-256 で検証する。
- **Windows 保存先**: `%LOCALAPPDATA%\ZeroConIME\models\`（0.8B 既定、任意で 2B も選択可）。
- **Android 保存先**: アプリ内 `files/translation/`（0.8B のみ）。詳細は [windows-translation-input.md](investigations/windows-translation-input.md) および [android-translation-input.md](investigations/android-translation-input.md) を参照。

## 検証

共有設定の旧形式読み込み（`"cuda"` → `"vulkan"` 移行を含む）・モデル役割・名前と探索、モデル一覧、launcher、ベンチマーク、翻訳モデル検証を回帰テストする。GPU推論や設定画面の実操作と、単体テストの成功は区別する。
