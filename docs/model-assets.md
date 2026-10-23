# ZeroConモデルとバックエンドの配置

配布用モデルの正本は `artifacts/models/zerocon/`。インストール後は `{app}/models/` に同じ3ファイルを配置する。

| 表示名 | 配布ファイル名 | 元のファイル名 | 容量 |
| --- | --- | --- | --- |
| ZeroCon Model Standard | `zerocon-model-standard.gguf` | `zenz-v3.2-small-q4km.gguf` | 64,282,688 bytes |
| ZeroCon Model Nano | `zerocon-model-nano.gguf` | `zenz-v3.2-xsmall-q4km.gguf` | 18,452,288 bytes |
| ZeroCon Model Corrector | `zerocon-model-corrector.gguf` | `typo-corrector-xsmall-q4km.gguf`（現在の動作版） | 18,452,384 bytes |

合計101,187,360 bytes。重みの内容やGGUFメタデータは変更せず、配布名と配置を統一する。由来とSHA-256は `windows/deployment/model-catalog.json` に固定する。名前が同じ実験版でもハッシュが異なる場合は自動採用しない。

`Build-Package.bat`（およびパッケージを新規作成する `Build-Installer.bat`）は `sync-distribution-models.ps1` を呼び、正本がない場合だけ指定した依存保管先または `artifacts/models/` からハッシュが一致する重みを集約する。正本が変わっていれば停止する。モデル更新時はカタログの由来・ハッシュも更新する。実験用の別量子化版・学習出力、稼働中のIME、過去のインストーラー出力は自動削除しない。既存の学習・評価スクリプトの参照を保持するためである。ローカルの `artifacts/models/model-inventory.json` には現存ファイルとハッシュ・配布役割の対応を記録する。

変換・先読みは設定 `zenzai.model` の `standard` / `nano` で重みを選ぶ。Correctorの独自モデル名は **corecctor**。`Miwa-Keita/zenz-v3.1-xsmall` を日本語入力のタイポ補正向けに追加学習し、Q4_K_Mに量子化したモデルで、ベースモデルと派生モデルはCC BY-SA 4.0。表記と帰属は `windows/deployment/THIRD_PARTY_LICENSES.md` を参照。Correctorは補正専用で、変換モデルの選択肢には混ぜない。従来の `standard_q4km` / `nano_q4km` や旧ファイル名も読み取り互換を残す。モデル切り替えは設定保存とサーバー再起動で行い、Program Filesの重みやAppDataのミラーへコピーしない。新規配布にはルートの `zenz.gguf` を入れない。

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
