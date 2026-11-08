# ファイル探索ガイド

表の `W/` は **`windows/`**、`A/` は **`android/app/src/`** の略。この文書内だけの表記。構造・フロー・実行コマンドは [architecture.md](architecture.md) に集約する。

## 症状から探す

| タスク | 最初に読む → 必要なら読む |
| --- | --- |
| タイポ候補が生成されない・誤補正する | `W/crates/server/src/ai_assist/mod.rs` の `fetch_typo_suggestion` → 同階層の `romaji_decoder.rs` / `typo_variant.rs` / `learning.rs` |
| 元の打鍵列による隣接キー補正 | `W/crates/server/src/ai_assist/keyboard_hybrid.rs` → `W/native/keyboard-prior/`。生成・配布は `W/scripts/build-keyboard-prior.ps1` / `build-trial.ps1` / `deployment/model-catalog.json` |
| 補正があるのに表示・Tab採用できない | `W/crates/server/src/main.rs` の `append_ai_assist_candidates` → `W/crates/client/src/engine/composition.rs` の `ai_assist_priority_index` と候補フィルタ・キー処理 → `W/crates/ui/src/ruby.rs` |
| Swift変換側のタイポ補正 | `W/server-swift/Sources/azookey-server/azookey_server.swift` の `getOptions` / `confidentKeyboardTypoCorrectionForZenzaiMerge` → `W/data/keyboard-typo-dictionary.tsv` |
| 先読みが不自然・出ない | `W/crates/server/src/main.rs` の先読み呼び出し → `W/crates/server/src/ai_assist/mod.rs` の `fetch_predictive_candidate_internal` → `collocation.rs` → launcherの起動モデル |
| 入力遅延・モデル接続・設定と違うモデル | `W/crates/launcher/src/main.rs` の `start_ai_server_process` → `W/crates/server/src/ai_assist/mod.rs` の `simple_http_post` → 配布先のモデル・backend |
| 自動確定・削除・カーソル・文節の不整合 | `W/crates/client/src/engine/composition.rs` → `composition/tests/` → `W/crates/server/src/composition_owner.rs` / Swiftの対応操作 |
| 選択範囲の再変換・文字数表示 | `W/crates/client/src/engine/composition.rs` の再変換キー判定 → `W/crates/client/src/tsf/edit_session.rs` / `selection_count.rs` |
| 設定変更が反映されない | `W/crates/shared/src/lib.rs` → `W/frontend/src/lib/config.ts` / `pages/general.tsx` → `W/frontend/src-tauri/src/lib.rs` → serverの `reload_ai_assist_from_settings` / Swiftの `LoadConfig` |
| 起動・DLL登録・配布差分 | `ZeroConvertIME-windows/Start-ZeroConvertIME.ps1` / `Status-ZeroConvertIME.ps1` → `W/crates/launcher/src/main.rs` → `W/Makefile.toml` / `installer/Installer.iss` |
| モデル切替・画面内ベンチマーク | `W/frontend/src/pages/zenzai.tsx` / `benchmark.tsx` → `W/frontend/src-tauri/src/lib.rs` の `switch_model` / `run_ai_assist_benchmark_item` |
| 学習モデル・量子化比較 | 目的の `training/train_*.py` / `training/export_*.py` → `benchmarks/research/benchmark_nano_v2.py` / `benchmark_typo_300.py` → 対応する `artifacts/results/` |

探索例: `rg -n 'fetch_typo_suggestion|TYPO_CORRECTION' windows/crates/server`。まず対象ディレクトリと拡張子を絞り、ワークスペース全体の再帰検索は必要になってから行う。

## ディレクトリの責務

| パス | 責務・探索上の扱い |
| --- | --- |
| `ZeroConvertIME-windows/` | ローカルWindows配布・実行物。運用PowerShell、初期設定、DLL/EXE、辞書、モデル、バックエンド。開発ソースとは区別 |
| `W/crates/client/` | TSF DLL、キー・編集セッション・composition・IPC |
| `W/crates/server/` | Rust変換RPCサーバー、Swift FFI呼び出し、composition所有権、AIアシスト |
| `W/native/keyboard-prior/` | 元の打鍵列・辞書・小型読みモデルを使う補正専用ワーカーの正本。研究用C++実行器もこの実装を共有 |
| `W/crates/shared/` | IPC契約、共通設定、候補ID、文字幅・ローマ字規則 |
| `W/crates/ui/` | 候補一覧・読み表示・入力モード表示、表示用IPC |
| `W/crates/launcher/` | セッション単位の子プロセス起動・監視、llamaバックエンド選択 |
| `W/crates/macros/` | Rust手続きマクロ |
| `W/server-swift/` | Swift変換ラッパー、FFI、変換・辞書テスト、辞書サブモジュール |
| `W/frontend/src/` / `src-tauri/` | React設定画面 / Rustの設定保存・IPC・更新・モデル切替・評価コマンド |
| `W/data/` | Windowsタイポ辞書の選択データ・規則・NOTICE・評価方針。ルートの学習用 `data/` とは別 |
| `W/scripts/` / `installer/` | 生成器・検証・VM補助 / Inno Setup配布定義 |
| `W/docs/` / `.github/workflows/` | 上流系の開発・検証・配布手順 / CI。ワークスペースの本 `docs/` とは別 |
| `android/` | Androidアプリ・Gradle設定・JNI・ローカル単体テスト。Gradleモジュールは `:rebuilt` だけで、`android/app/` は共有ソースの置き場 |
| `engine/` | WindowsとAndroidが共有するRust/Swift/native。`Package.resolved` がAndroidのSwift pin、`shared-engine.lock.json` がAPKと突き合わせるエンジン指紋 |
| `data/` | 学習・検証・ベンチマークJSONLと生成Python |
| `benchmarks/research/` | モデル比較、タイポ・文脈・先読み評価、試作 |
| `training/` | モデル学習、LoRAマージ、GGUF変換補助 |
| `benchmarks/` | 複数方式・量子化等の評価 / 結果解析。目的別に1本ずつ読む |
| `experiments/legacy/` | 過去のコード書換スクリプト・調査断片。通常の開発には使わない |
| `artifacts/models/` | 学習・量子化したGGUFや比較用モデル。配布先 `ZeroConvertIME-windows/models/` とは別 |
| `artifacts/checkpoints/` | LoRA、マージ済み重み、全層学習、チェックポイント等の実験成果物 |
| `artifacts/results/` / `artifacts/logs/` | 評価JSON / ログ。実装の一次情報にはしない |
| `docs/` | 現行の仕様書・設計書・開発手順（恒久文書）。個別調査は `docs/investigations/` |
| `reports/` | 過去の実験・実装調査レポート。`models/`（モデル実験）、`typo-assist/`（タイポ・先読み）、`port-history/`（移植記録）に分類 |
| `scratch/` | このPCだけの旧チェックアウト・調査資料保管先。新規クローンには含まれない |
| `.venv/` / `.venv_train/` / `__pycache__/` | Python環境・キャッシュ。通常のソース探索から除外 |

## 主要ファイルの補助地図

Windowsの中核5ファイルは [ルートガイド](../AGENTS.md) を参照。以下はそれ以外の関連ファイル。

| ファイル | 役割 |
| --- | --- |
| `W/crates/client/src/tsf/key_event_sink.rs` / `edit_session.rs` | TSFキー入口 / 編集セッション |
| `W/crates/client/src/tsf/selection_count.rs` | 選択変更通知、非同期文字数カウント、フォーカスを奪わない文字数表示 |
| `W/crates/client/src/engine/ipc_service.rs` | 変換サーバーとの通信 |
| `W/crates/server/src/composition_owner.rs` | 操作元接続とcompositionの所有管理 |
| `W/crates/shared/service.proto` / `window.proto` | 変換・管理RPC / 表示メッセージ |
| `W/crates/server/src/ai_assist/romaji_decoder.rs` | ローマ字子音混入の復元 |
| 同 `typo_variant.rs` / `builtin_vocab.tsv` | QWERTY変種・軽量辞書 / 埋込み語彙 |
| 同 `learning.rs` / `collocation.rs` / `dynamic_macro.rs` | 補正採用学習 / 共起語予測 / 日時・計算等の展開 |
| `W/server-swift/Sources/ffi/include/ffi.h` / `ffi.c` | C ABIの宣言と橋渡し |
| `W/server-swift/Sources/azookey-server/romaji_table_compiler.swift` | カスタムローマ字規則のコンパイル |
| `W/frontend/src/lib/config.ts` | TypeScript側の設定型・既定値 |
| `W/frontend/src-tauri/src/ipc.rs` | 設定アプリからサーバーへのIPC |
| `W/Makefile.toml` / `.github/workflows/actions.yml` | 全体ビルド・配布コピー / 正式なチェック定義 |
| `W/app-version.json` / `scripts/sync-version.mjs` | 版の入力 / メタデータ同期・生成 |
| `A/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt` | Android IMEライフサイクルと入力連携 |
| 同 `KanaKanjiConverter.kt` / `ZenzNative.kt` | Kotlin変換処理 / native呼び出し |
| 同 `assist/TypoCorrectionEngine.kt` / `PredictiveAssistEngine.kt` | Android固有の補正 / 先読み |
| 同 `translation/` | 翻訳プロンプト・モデルDL/検証・世代管理・遅延ロード（試験機能） |
| 同 `model/ModelManager.kt` | 組込み・取込みモデル選択 |
| `A/main/cpp/zenz_jni.cpp` / `assist_jni.cpp` / `azookey_louds.cpp` | 推論JNI / assist・翻訳Runtime / 辞書読取。nativeのビルド定義は `engine/native/CMakeLists.txt` |
| `data/generate_typo_dataset.py` / `generate_word_typo_30k.py` | タイポ学習データ生成 |
| `data/build_distillation_corpus.py` / `generate_distilled_dataset.py` | 蒸留コーパス関連処理 |
| `training/train_nano_v2.py` / `training/train_nano_v2_gentle.py` / `training/train_nano_v2_fullft.py` | 蒸留LoRA / gentle版 / 全層学習 |
| `training/train_typo_corrector.py` / `training/export_typo_corrector.py` / `training/export_nano_v2.py` | タイポ学習 / 各LoRAのマージ |

## テスト・評価を選ぶ際の注意

- Windows入力状態は `W/crates/client/src/engine/composition/tests.rs` と `composition/tests/`。サーバーの補正優先・先読み提示テストは `W/crates/server/src/main.rs`、補正生成テストは `ai_assist/` 内にある。
- Swiftの変換テストは `W/server-swift/Tests/azookey-serverTests/`。フロント保存処理は `W/frontend/tests/`。Android単体テストは `A/test/java/com/zeroconvert/ime/`。
- `benchmarks/research/benchmark_typo_300.py` はPythonへ移植したルール表とモデルを比較する。`benchmarks/research/benchmark_windows_ai_assist.py` も独自の補正表・キャッシュを含む。現行Rustパイプラインの直接テストではない。
- Tauriの `benchmark_moshikashite` も独自の補正処理を持つため、設定画面の結果と実IMEの結果を同一視しない。
- `benchmarks/research/test_server_ai_assist_live.py` はimportと案内表示だけで、実IMEの動作検証になっていない。`experiments/legacy/test_pipe.py` も古い固定Pipe名を持つ探索用断片。
- `scratch/verify_predictive_phrase_matching.py` は旧 `ai_assist.rs` パス・旧ロジックを参照する。現在の `ai_assist/mod.rs` に対する回帰テストとしてそのまま使わない。

## 直接編集しない出力・依存物

| 対象 | 変更元・扱い |
| --- | --- |
| `W/server-swift/Sources/azookey-server/KeyboardTypoDictionary.swift` | 生成済み。`W/data/keyboard-typo-dictionary.tsv` と `W/scripts/generate-keyboard-typo-dictionary-swift.mjs` から再生成。NOTICEを維持 |
| `W/target/`、各crateの `target/`、`W/build/`、`server-swift/.build/`、フロント `dist/` | ビルド出力・生成Protobuf・取得依存。`.proto`・実ソース・ビルド定義を変更して再生成。`W/build/` はビルド時の出力先で、調査時点では存在しない |
| `W/target/azookey-build/`、配布 `build-info.json` | `app-version.json` と `scripts/sync-version.mjs` が生成。frontendの版フィールドも同スクリプトの同期対象 |
| `Dictionary/Reverse/reverse-v2.bin` | `W/scripts/generate-reverse-dictionary.mjs` で生成 |
| `node_modules/`、`.gradle/`、Android `rebuilt/build/` / `.cxx/`、`engine/build/` | 依存・ビルド生成物。Gradle/npm/CMakeの入力を変更 |
| Android `app/src/main/cpp/llama.cpp/`、Swift取得依存・辞書サブモジュール | 外部コード・データ。変更が必要なら依存元とライセンス、更新方法を先に確認 |
| 配布先のDLL/EXE、`.lib`/`.obj`/`.exp`、GGUF/safetensors、checkpoint | 手編集しない。ソース・学習入力から作る。`.old*` / `.bak*` / `.tmp` は現行ソースとみなさない |

依存・ビルド用ディレクトリを初手の検索対象から外すことと、削除してよいことは別。ローカル成果物や未コミットの作業を保持する。
