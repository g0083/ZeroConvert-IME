# ZeroConモデルとバックエンドの配置

通常変換・先読みの選択肢はWindows・Android共通で次の2種類。新規導入はIQ4_emb8を既定とする。

| 設定ID | 配布ファイル名 | 容量 |
|---|---|---:|
| `nano_v4_iq4_emb8` | `zerocon-nano-v4-iq4nl-emb8.gguf` | 19,602,048 bytes |
| `nano_v4_q8` | `zerocon-nano-v4-q8_0.gguf` | 29,039,232 bytes |

IQ4_emb8はNano v4のBlend85版。内部IQ4_NL、入力埋め込み・出力Q8_0。Q8は100%マージのQ8_0。どちらもzenz-v3.2-xsmallを追加学習したApache-2.0派生モデル。SHA-256・元ファイル名・補正専用モデルの情報は `windows/deployment/model-catalog.json` を正本とする。

Corrector（corecctor）とKeyboard Priorは通常変換に混ぜず、補正専用として維持する。旧Standard/Nanoの重みやローカルの研究モデルは自動削除しないが、新規パッケージへは入れない。

## 配置と移行

Windowsの正本は `artifacts/models/zerocon/`、インストール先は `{app}/models/`。`windows/scripts/sync-distribution-models.ps1` がハッシュ一致する生成済みモデルを集約し、`Build-Package.bat`も同じ処理を利用する。選択は `zenzai.model` へ保存し、サーバー再起動で反映する。

旧 `standard` / `standard_q4km` はQ8へ、旧 `nano` / `nano_q4km` はIQ4_emb8へ読み替える。旧重みや `zenz.gguf` を新しいNano v4として扱うフォールバックはしない。モデル未配置なら新しい配布資産を準備する。

Androidは `scripts/prepare-android-conversion-models.ps1` でハッシュ検証後に `android/app/src/main/assets/models/` へコピーする。`:rebuilt` が同じ2種類を梱包し、Gradleの `verifyConversionModels` でサイズ・ハッシュを検査する。旧assetsはローカルに残してAPKの同期対象から除外する。既存の管理下Standard選択はQ8、Nano/Lite選択はIQ4_emb8へ移行する。ユーザーがインポートした別パスのモデルや補正履歴は保持する。

モデル重みはGit対象外。clone後はカタログと一致する重みを別途用意する。コピー・ビルド・実IMEへの適用・端末更新は別操作である。

## バックエンド

- CPU / Vulkanはインストーラーへ同梱する。
- LFMは配布・起動処理から外す。Correctorも他の機能と同じCPU / Vulkan / CUDAを使用する。
- CUDAは設定画面で選択した際、未導入ならダウンロード確認を表示する。確認前・キャンセル時は通信も設定変更も行わない。完了後だけCUDA設定を保存する。バックエンドの適用には再起動が必要。
- 追加取得は既存エンジンと互換性のあるb4846 / CUDA 12.4に固定する。エンジンは `fkunn1326/llama.cpp`、CUDAランタイムは `ggml-org/llama.cpp` のGitHub releaseから取得する。URL・正確なサイズ・SHA-256は `cuda_backend.rs` に固定。合計584,036,396 bytes（確認画面では約585 MB）。NVIDIAドライバーは別途必要で、自動インストールしない。
- 取得中は進捗を表示。ハッシュ一致後、必要な11ファイルだけを一時フォルダへ展開し、全ファイルを確認してからフォルダを公開する。任意のZIPパスを展開・実行しない。失敗時は一時ファイルを破棄し、現行設定・バックエンドを保持する。
- 保存先は `%LOCALAPPDATA%/ZeroConIME/backends/cuda-b4846-cu12.4/`。管理者権限やProgram Filesへの書き込みを必要としない。通常のインストーラー更新ではキャッシュを保持する。launcher・設定画面からの再起動・先読み・Corrector・ベンチマークは共通の `runtime_assets::backend` で探索する。
- 更新インストーラーは既知の旧同梱 `llama_cuda` / `llama_lfm` と旧名モデルだけをインストール先から削除する。配布外の研究モデルは対象外。

## 検証

共有設定の旧形式読み込み・モデル役割・名前と探索、モデル一覧、launcher、ベンチマーク、ZIP展開制限を回帰テストする。`cargo test -p frontend --lib cuda_backend -- --include-ignored` は `.local/downloads/` に固定アーカイブがある場合、両SHA-256・実展開・`llama-server --version` 起動も確認する。GPU推論や設定画面の実操作と、これらのテストの成功は区別する。
