# ZeroConモデルとバックエンドの配置

通常変換・先読みの選択肢はWindows・Android共通で Nano / Standard の2種類ずつ。モデルはインストーラー・APKに同梱せず、端末のキャッシュに無い場合に GitHub リリースのピン留めアセットから取得する（英字辞書・翻訳モデルと同じ方式）。

| 設定ID | 配布ファイル名 | 容量 | 取得URL（GitHub Release `models-v4.1`） |
|---|---|---:|---|
| `nano_v4_1_iq4_emb8`（**強制**・既定） | `zerocon-nano-v4.1-iq4nl-emb8.gguf` | 19,602,048 bytes | `…/download/models-v4.1/zerocon-nano-v4.1-iq4nl-emb8.gguf` |
| `nano_v4_1_q8` | `zerocon-nano-v4.1-q8_0.gguf` | 29,039,232 bytes | `…/download/models-v4.1/zerocon-nano-v4.1-q8_0.gguf` |
| `standard_v4_1_iq4_emb8` | `zerocon-standard-v4.1-iq4nl-emb8.gguf` | 61,399,072 bytes | `…/download/models-v4.1/zerocon-standard-v4.1-iq4nl-emb8.gguf` |
| `standard_v4_1_q8` | `zerocon-standard-v4.1-q8_0.gguf` | 103,866,400 bytes | `…/download/models-v4.1/zerocon-standard-v4.1-q8_0.gguf` |

IQ4_emb8はNano / Standard v4.1の省メモリ版（内部IQ4_NL、入力埋め込み・出力Q8_0）。Q8はQ8_0高精度版。どちらもzenz-v3.2-xsmall / zenz-v3.2-small（Apache-2.0）に追加学習した独自改変モデルで、上流のApache-2.0条件は同梱表示で満たすが、重みの再利用・再配布は許諾しない。SHA-256・元ファイル名・モデル仕様の正本は `windows/deployment/model-catalog.json` とする（取得URLは `releases/download/models-v4.1/<filename>`）。

Standard v4.1 は `standard-v4-cap-nam-r3`（Nano v4.1 と同じ v4.1 調整譜面で学習した Standard 中規模版）の量子化済みファイルで、配布ファイル名は `zerocon-standard-v4.1-*.gguf`。v4.1 Q5_K_M（Nano / Standard）も作成・ベンチマークしたが 480問で Nano 453/480、Standard 443/480（埋め込み保護版でも 448/480・442/480）と IQ4/Q8 より明確に劣るため選択肢に採用しない。結果は `artifacts/results/v41_q5km_cpu_*.json` を参照。

旧設定・旧ファイルとの互換移行にのみ対応し、旧 `zenz.gguf` 等をそのまま現行モデルとしてフォールバック読み込みはしない。モデル未配置なら新しい配布資産を準備する。

## 配置と取得

- **Windows**: 設定画面（ZeroCon AI）で不足モデルをダウンロードし、`%LOCALAPPDATA%\ZeroConIME\models\` にキャッシュする。選択は `zenzai.model` へ保存し、サーバー再起動で反映する。`shared::runtime_assets::converter_model` はキャッシュを優先し、旧配布物に残った `models\` だけを互換先として見る。`windows/scripts/prepare-distribution.ps1` はモデルを payload へコピーしない。`windows/scripts/sync-distribution-models.ps1` は canonical ストア（`artifacts/models/zerocon/`）がカタログと一致するか検証するだけ。
- **Android**: 設定画面で不足モデルをダウンロードし、`files/models/` にキャッシュする。nano IQ4 は**強制**として初回表示・取得確認を行う（無い間は辞書変換のみ）。`:rebuilt` は `*.gguf` を梱包対象から除外し、Gradle の `verifyConversionModels` はカタログ形式と APK 無同梱を検査する。ユーザーがインポートした別パスのモデルや補正履歴は保持する。
- **配布元**: 4ファイルは GitHub リリース `models-v4.1` のアセットに置く。アプリ内の取得URL・サイズ・SHA-256は上表のとおり。タグやホストを変更する場合は `windows/crates/shared/src/runtime_assets.rs`（`MODEL_RELEASE_TAG`）、`android/app/.../model/ModelManager.kt`（`RELEASE_DOWNLOAD_BASE`）、`windows/deployment/model-catalog.json` をそろえる。

旧 `nano_v4_q8` / `standard` / `standard_q4km` はNano v4.1 Q8へ、旧 `nano_v4_iq4_emb8` / `nano` / `nano_q4km` はNano v4.1 IQ4_emb8へ読み替える。旧重みや `zenz.gguf` を新しいモデルとして扱うフォールバックはしない。モデル未配置なら新しい配布資産を準備する。

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
