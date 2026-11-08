# 実装構成・実行手順

2026-10-24追記: WindowsのSwift/Rustを直接利用するAndroid `:rebuilt` を追加。
従来Androidの独立実装と区別し、[新構成](android-shared-engine.md) を参照する。

2026-10-22のローカルコード・設定を確認し、ソース整理後のパスに更新した記録。Windows/Androidのフルビルド・IME起動は未検証。移行時の保全・検証結果は [setup.md](setup.md)、詳細な探索先は [file-map.md](file-map.md)。

## 技術スタックと境界

| 対象 | 実装・設定根拠 |
| --- | --- |
| Windows IME | Rust 2021、Windows TSF/COM、Tokio、Tonic/Protobuf。`windows/Cargo.toml` と各crateのmanifest |
| かな漢字変換 | Swift（Package tools 6.1）、C/C++ FFI、AzooKeyKanaKanjiConverterのZenzai機能、GGUF/llamaバックエンド。`server-swift/Package.swift` |
| 設定UI | Tauri 2、React 18、TypeScript 5.6、Vite 6、Tailwind CSS 4。`frontend/package.json` |
| 運用・配布 | PowerShell、Node.js生成スクリプト、cargo-make、Inno Setup |
| Android | Kotlin 1.9.23、AGP 8.3.2、Compose、Coroutines、JNI/C++17、CMake/llama.cpp。SDK 34、minSdk 26、Java 17、NDK 27.1.12297006 |
| 学習・研究 | Python、PyTorch、Transformers、Datasets、PEFT/LoRA。根拠は `training/train_nano_v2.py`、`training/train_typo_corrector.py` 等のimport・処理 |

WindowsのRust toolchain指定は `windows/rust-toolchain.toml` の `1.98.1`。上流の参考CIはNode 22とSwift 6.1.2を指定する。Swift変換依存はPackage.swiftでコミット `c228776b0b869f81ee2a1031ff9dbd679f4b3cd9` に固定される。

## Windowsのモジュール関係・データフロー

```text
入力先アプリ
  → client DLL (TSFキーイベント・composition状態)
  → engine/ipc_service.rs
  → セッション別Named Pipe上のTonic RPC
  → server/main.rs (composition所有者・操作順序を管理)
  → C ABI → Swift azookey_server.swift
  → KanaKanjiConverterModule (辞書 + Zenzai / zenz.gguf)
  → 通常変換候補
  → Rust ai_assistで追加候補を生成
  → clientへ応答 → TSF composition更新 / ui.exeへ表示
  → 確定通知 → 通常変換の学習 または AIアシスト専用の記録
```

- `shared/service.proto` が変換RPC、`shared/window.proto` が表示側の契約。`shared/build.rs` がRust APIを生成する。
- 候補ウィンドウのクリック採用は逆方向の通知になる。client DLLがスレッドごとにメッセージ専用ウィンドウを作り（`tsf/engine_message_window.rs`）、`UpdateCandidateWindow` でHWNDをui.exeへ告知する。ui.exeはクリックで `WM_APP` 系メッセージ（`shared::ENGINE_CANDIDATE_SELECT_MESSAGE`）をそのHWNDへ投げ、engineが `handle_external_candidate_selection` で選択＋Enter確定と同じ経路を実行する。
- `CompositionOwner` は接続元のcomposition所有権を管理する。削除・文節移動・再変換を含め、クライアント予測状態とサーバーの正規状態を混同しない。
- `launcher.exe` がサーバーと候補UIを起動・監視する。`Start-ZeroConvertIME.ps1` もlauncherを起点にする。サーバーやUIの単独起動は孤立プロセス・Pipe競合の原因になると同スクリプトに記載がある。
- 設定UIはTauriコマンド→`shared::AppConfig`による保存→IPCでサーバー適用、という別経路。モデル切替・ベンチマークにも独自処理がある。

### 変換・補正・自動確定は別機能

1. **通常変換**: Swiftが実行ファイル隣の `zenz.gguf` と辞書を使う。`general.experimental_typo_correction` はSwiftのキーボードタイポ補正へ渡される。
2. **Rustの「もしかして」**: `general.experimental_ai_assist` 配下。浮いたローマ字子音、採用済み学習、固定タイポ表、語中・音韻規則、編集距離、QWERTY辞書検索、末尾最大8文字のニューラル補正へ順次フォールバックする。
3. **先読み**: 補正候補が出ていれば生成を省略する。共起語を優先し、それがなければ定型句→確定履歴→同一プロンプトキャッシュ→モデル推論へ進む。モデル推論には60msの連続呼び出し抑制と同時リクエスト抑制がある。
4. **アシストの採用**: 候補ID `999901`（補正）/`999902`（先読み）は通常候補一覧から除かれ、読み表示のヒントとTab採用に使われる。`999903` は動的マクロ。予約IDはサーバーで処理し、Swiftの通常候補学習に渡さない。
5. **ライブ変換の自動確定**: client側。全体の読み16文字以上、確定部分8文字以上、残り7文字以上、表記接頭部4文字以上、5段階の安定性などを判定し、自然な境界で先頭部分を確定する。定数だけでなく `find_auto_commit_live_conversion_snapshot` 等の条件を確認する。

### 設定・状態の保存先

2026-10-28追記: Windowsのembeddedアシストに、元のローマ字打鍵列から隣接キー1文字の候補を作る経路を追加した。`ai_assist/keyboard_hybrid.rs` が別プロセス `llama_cpu/keyboard-prior.exe` を管理し、AZR2辞書と追加の18.45MBモデル `models/zerocon-model-keyboard-prior.gguf` で最大8読みを採点する。既知の固定補正・学習済み補正は従来経路を優先する。新経路は手動採用専用で、通常変換候補を保持する。候補表記は既存の予測モデルへ問い合わせ、読み一致の検証に失敗・時間切れなら補正済みのひらがなを使う。

ワーカー初期化は背景処理、要求はtry_lockと250ms期限で制限する。終了・応答失敗時は子プロセスを破棄して再起動する。TSF側はタイマーで結果を受け取り、先読みが先着した場合だけ初回表示を最大200msまで待ち合わせる。入力処理を同期で待たせない。実装・適用・確認範囲は [実IME検証記録](../reports/typo-assist/keyboard-hybrid-ime-20261028.md) を参照。上記の2026-10-22時点の経路説明とは区別する。

- GPUの層数によるRAM/VRAMの配分と、Windows再起動を伴わないバックエンド切替は [Windows推論のメモリ設定](investigations/windows-inference-memory.md) を参照。`zenzai.gpu_layers` の既定値は99（全層）、CPUでは0層として扱う。

- 実設定は `%APPDATA%\Azookey\settings.json`。配布フォルダの `settings.json` は初期コピー元であり、既存設定があればStartスクリプトは上書きしない。
- 補正学習は同ディレクトリの `ai-assist-learning.json`（最大500件）、追加補正語彙は `ai-assist-vocabulary.json`。先読みの確定履歴キャッシュはRustプロセス内に最大300件保持する。
- `shared/src/lib.rs` のPipe名は `\\.\pipe\LOCAL\azookey_{server,ui,launcher}_s<sessionId>`。古い固定名やセッション番号をハードコードしない。
- 配布設定の確認値はZenzai有効、Vulkan、タイポ補正・AIアシスト・自動確定有効、予測モデル `230m_q4km`、engine `embedded`。これは実行中のAppData設定の値を保証しない。

### 調査で確認した制約・未確認点

- launcherはポート18091でメイン変換モデル（`zenz.gguf`、Nano v4等）の常駐推論サーバーを起動・監視する。従来の独立したCorrectorサーバー（18090番）およびLFMモデルはv0.2.3で完全撤廃された。
- 先読みおよびタイポ補正（Stage-2 表層リランキングおよび再変換）はともに18091番のメイン変換モデルを利用する。
- 設定画面のベンチマークは専用の8090番で起動し、実IMEのプロセスとは分離されている。
- `experimental_ai_engine_port` 等の旧ポート設定は過去の互換性項目であり、現在の実運用では18091番（メイン変換）と8090番（設定UIベンチマーク）に整理されている。
- `ai_assist/mod.rs` の冒頭コメントには推論エンジン非依存とあるが、現実のフォールバックには同期TCP/HTTPがある。タイポ補正150ms等のタイムアウトも含めて遅延を調べる。
- 先読み関数の引数名は `partial_surface` だが、serverからは `hiragana_text` が渡る。定型句表には漢字表記もあるため、読み・変換済み表記・継続文字列の境界を追う。
- 配布READMEのモデル精度・版数、`build-info.json` の上流版情報だけでは、現在のローカルソースや配布バイナリの品質・対応関係を確定できない。今回、起動状態・重みの由来・再現精度は検証していない。

## モデル作成とAndroid

学習経路は `data/` のJSONL生成→`training/train_*.py`によるLoRA/全層学習→`training/export_*.py`による重みのマージ→GGUF変換・量子化→評価。`training/export_nano_v2.py` 自体はGGUF変換器ではない。`training/train_nano_v2.py` の既定baseは `Miwa-Keita/zenz-v3.1-xsmall`、教師データはzenz-v3.2-small系の蒸留・辞書データ。配布名ZeroConと、ファイル名zenz/typo-corrector/nano-v2を区別し、各モデルの採用状況を名前から推測しない。

Androidは `ZeroConvertInputMethodService` → `KanaKanjiConverter` / assist → `ZenzNative` → JNI/llama.cpp・LOUDS辞書という別実装。モデルは `ModelManager` がassetsとアプリ内filesから管理する。Windowsとの共有RPCや共通Rustエンジンがある前提で修正しない。2026-11-05に試験的な翻訳入力を追加した。PLaMo 3 Translate 0.8BはAPKに同梱せず、設定画面の確認後にHugging Faceから `files/translation/` へダウンロードし、`assist_jni.cpp` の独立したTranslation Runtime（mmap、別epoch）で推論する。詳細は [Android翻訳入力](investigations/android-translation-input.md)。

## ビルド・実行・検証コマンド

以下のWindows開発コマンドの作業ディレクトリは **`windows/`**。定義元は同ディレクトリ内の `Makefile.toml`、`frontend/package.json`、上流参考 `.github/workflows/actions.yml`。実際のルートCIは `.github/workflows/checks.yml`。MSVC/Windows SDK、指定Rust、Swift for Windows、protoc、Node/npm、cargo-make、配布時はInno Setupが必要。辞書サブモジュール、llamaライブラリ・ランタイム・モデルも別途必要になる。

| 用途 | コマンド |
| --- | --- |
| フロント依存準備（`frontend/` 内） | `npm ci` |
| Swiftビルド | `swift build --package-path server-swift -c release -Xcc -D_CRT_USE_C_COMPLEX_H -Xcxx -D_CRT_USE_C_COMPLEX_H` |
| Rust x64 | `cargo build --release` |
| TSF DLL x86 | `cargo build -p azookey-windows --target=i686-pc-windows-msvc --release` |
| 設定UI開発（`frontend/` 内） | `npm run tauri dev`（Viteのみは `npm run dev`） |
| 設定UIビルド（`frontend/` 内） | `npm run tauri:build` |
| インストーラーまでの全体ビルド | `cargo make build --release`（debugは `cargo make build --debug`） |
| Rustテスト | `cargo test --workspace --all-targets` |
| Swiftテスト | `swift test --package-path server-swift -c release --verbose -Xcc -D_CRT_USE_C_COMPLEX_H -Xcxx -D_CRT_USE_C_COMPLEX_H` |
| Rust書式チェック / lint | `cargo fmt --all -- --check` / `cargo clippy --workspace --all-targets -- -D warnings` |
| 設定UI型検査 / テスト（`frontend/` 内） | `npm run typecheck` / `npm test` |
| 候補スクロールJSテスト | `node --test crates/ui/tests/candidate_scroller.test.cjs` |
| 逆引き辞書生成器テスト | `node --test scripts/generate-reverse-dictionary.test.mjs` |

`cargo make build` は書式整形、版情報同期、辞書生成、複数のビルド、コピー、インストーラー生成を伴う。確認だけのコマンドではない。フロントのprebuildも版情報を同期する。定義済みのnpm `lint` スクリプトはない。

Swift/Rustのリンク準備はCIのWindowsテストjobを参照する。`llama_cpu/llama.lib` を `server-swift/` へ配置し、Swift生成の `azookey-server.lib` をソースルートと `crates/server/target/` に配置する。Rustサーバーの `build.rs` が後者を検索する。SwiftのDLL・テストランタイムとllama DLLもPATH上に必要。全体ビルドの既定コピー経路だけでローカルの全テスト準備が完了するとは限らない。

実行・状態確認は、このワークスペースのルートから:

```powershell
powershell -ExecutionPolicy Bypass -File .\ZeroConvertIME-windows\Status-ZeroConvertIME.ps1
powershell -ExecutionPolicy Bypass -File .\ZeroConvertIME-windows\Start-ZeroConvertIME.ps1
powershell -ExecutionPolicy Bypass -File .\ZeroConvertIME-windows\Restart-ZeroConvertIME.ps1
```

初回登録は同ディレクトリの `Install-ZeroConvertIME.ps1`。TSF/スタートアップ登録・設定作成・起動を行うため、単なるビルド確認として実行しない。

Androidは **`android/`** 内で `./gradlew.bat :rebuilt:assembleDebug`、`./gradlew.bat :rebuilt:testDebugUnitTest`、`./gradlew.bat :rebuilt:lintDebug`。モジュールは `:rebuilt` だけ。SDK/NDK・JDKと `local.properties` のローカル設定が必要。

Python評価はルートから `python scripts/run.py benchmarks/research/benchmark_nano_v2.py`、`python scripts/run.py benchmarks/research/benchmark_typo_300.py` が入口候補。前者はCUDA版llama-serverと複数GGUF、後者もローカルモデルを前提にサーバーを起動する。実行前にパス・ポート・出力先を読む。仮想環境の依存充足やGPUの利用可能性は本調査では確認していない。実IMEを経由しない評価のため、Windows動作確認とは別に扱う。

日常の開発では `Build-Trial.bat` が入力グループごとのソース指紋と前回の適用記録を比較し、必要なコンポーネントだけ増分ビルドして `.local/trial-updates/` の差分から適用する。変更がない場合はビルド・再起動を行わない。差分適用後の `trial-deployment.json` は `mode=incremental` となり、配布パッケージとの同一性を主張しない。配布前には全体の共通パッケージ作成・適用・確認を実施する。
