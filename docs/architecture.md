# 実装構成・実行手順

本リポジトリは Windows 版・Android 版（`:rebuilt` + 共有エンジン `engine/`）・ブラウザ体験版（`web/`）・モデル学習/評価系で構成される。詳細なファイル探索先は [file-map.md](file-map.md)、ドキュメント索引は [README.md](README.md) を参照。

## 技術スタックと境界

| 対象 | 実装・設定根拠 |
| --- | --- |
| Windows IME | Rust 2021、Windows TSF/COM、Tokio、Tonic/Protobuf。`windows/Cargo.toml` と各crateのmanifest |
| かな漢字変換 | Swift（Package tools 6.1、ローカルは Swift 6.4.1）、C/C++ FFI、AzooKeyKanaKanjiConverter（Zenzai / 確定後予測 / 継続変換）。`windows/server-swift/Package.swift` |
| 設定UI | Tauri 2、React 18、TypeScript 5.6、Vite 6、Tailwind CSS 4。`windows/frontend/package.json` |
| 運用・配布 | PowerShell、Node.js生成スクリプト、Inno Setup（`Build-Trial.bat` / `Build-Package.bat` / `Build-Installer.bat`） |
| Android | Kotlin 1.9.23、AGP 8.3.2、Compose、Coroutines、JNI/C++17、CMake/llama.cpp。SDK 34、minSdk 26、NDK 27.1.12297006（正本: `android/gradle.properties`）。変換・アシスト中核は `engine/` 経由で Windows の Swift/Rust ソースを共有 |
| ブラウザ体験版 | Swift Wasm / Rust `wasm32-wasip1` / llama.cpp 単一スレッド Wasm + Web Worker。`web/` |
| 学習・研究 | Python（内部で管理） |

WindowsのRust toolchain指定は `windows/rust-toolchain.toml` の `1.98.1`。Swift変換ライブラリは `windows/scripts/prepare-modern-llama.py` が AzooKeyKanaKanjiConverter（`c228776b0b869f81ee2a1031ff9dbd679f4b3cd9`）と llama.cpp（`7d516e2` / b12125）を取得・パッチ適用して `artifacts/dependencies/azookey-b12125` と `artifacts/runtime/windows-b12125` へ展開し、`Package.swift` から参照する。

## Windowsのモジュール関係・データフロー

```text
入力先アプリ
  → client DLL (TSFキーイベント・composition状態・InputScope追従・英字補完)
  → engine/ipc_service.rs
  → セッション別Named Pipe上のTonic RPC
  → server/main.rs (composition所有者・操作順序を管理)
  → C ABI → Swift azookey_server.swift
  → KanaKanjiConverterModule (辞書 + Zenzai / 確定後予測 + 変換学習)
  → 通常変換候補
  → Rust ai_assistで追加候補を生成（タイポ補正 / 文の先読み / 動的マクロ / 翻訳）
  → clientへ応答 → TSF composition更新 / ui.exeへ表示
  → 確定通知 → 通常変換の学習 または AIアシスト / 英字学習の記録
```

- `shared/service.proto` が変換RPC、`shared/window.proto` が表示側の契約。`shared/build.rs` がRust APIを生成する。
- 候補ウィンドウのクリック・右クリック忘却は逆方向の通知になる。client DLLがスレッドごとにメッセージ専用ウィンドウを作り（`tsf/engine_message_window.rs`）、`UpdateCandidateWindow` でHWNDをui.exeへ告知する。ui.exeはクリックで `WM_APP` 系メッセージ（`shared::ENGINE_CANDIDATE_SELECT_MESSAGE` / `ENGINE_CANDIDATE_FORGET_MESSAGE`）をそのHWNDへ投げ、engineが選択確定や候補単位の学習削除を実行する。
- `CompositionOwner` は接続元のcomposition所有権を管理する。削除・文節移動・再変換を含め、クライアント予測状態とサーバーの正規状態を混同しない。
- `launcher.exe` がサーバー（変換 / 先読み・翻訳の各 `llama-server`）と候補UIを起動・監視する。`Start-ZeroConvertIME.ps1` もlauncherを起点にする。サーバーやUIの単独起動は孤立プロセス・Pipe競合の原因になる。
- 設定UIはTauriコマンド→`shared::AppConfig`による保存→IPCでサーバー適用、という別経路。モデル切替・ベンチマーク・翻訳モデルDLにも独自処理がある。

### 変換・補正・先読み・自動確定・英字・翻訳の役割分離

1. **通常変換**: Swiftが設定で選ばれた ZeroCon Model（`nano_v4_2_iq4_emb8` / `nano_v4_2_q8` / `standard_v4_2_iq4_emb8` / `standard_v4_2_q8`）と辞書を使う。モデルと日本語辞書は配布物に同梱せずキャッシュから取得する（[model-assets.md](model-assets.md)・[dictionary-assets.md](dictionary-assets.md)）。`general.experimental_typo_correction` はSwiftのキーボードタイポ補正へ渡される（既定true、保存済みのfalseは維持）。Androidのタッチ位置は同じSwiftの `KeyboardCorrectionPolicy.swift` へ入力スナップショット付きで渡し、最大4件の辞書照合と候補の重み付けに使う。`PostCompositionPrediction.swift` により確定後の次語予測も通常変換側で扱う。
2. **タイポ補正のフォールバック**: 通常変換と同じ `general.experimental_typo_correction` 配下。共通Rustの `assist_policy::request_plan` が通常候補の補正済み読みと採用状態を調べ、未補正の場合だけRustの補正候補生成を実行する。採用済み学習、固定タイポ表、打鍵候補（Windows は `keyboard-prior.exe`、Android は `engine/native/keyboard_assist.cpp`）、メイン変換モデル（ポート18091）でのStage-2評価を使い、候補ID `999901` で提案する。なお旧 `zerocon-model-keyboard-prior.gguf` および独立 Corrector サーバー（ポート18090）は廃止済み。
3. **文の先読み**: `general.experimental_ai_assist` 配下で独立して切り替える。通常変換の表記と読みを基準に、ポート18091のメイン変換モデルで生成する。Swiftが通常候補を補正済みなら `predictionReading` で補正後の読みをRustへ渡す。共通Rustの `assist_reading` がこの読みを選び、`ai_assist/prediction.rs` が先読みを生成・検査する（`nam` 数値マスキング対応）。
4. **アシストの採用**: 候補ID `999901`（補正）/`999902`（先読み）の優先順位、表示対象の維持、採用時の置換／確定、取消と学習記録は `windows/crates/shared/src/assist_policy.rs` が担当する。WindowsはTab、Androidはタップから同じポリシーを呼ぶ。TSF/InputConnectionの入力先照合・文字列反映・復元用スナップショットは各OSのホストが管理する。`999903` は動的マクロ、`ENGLISH_LEARNING`（`999904`）は英字学習候補の削除対象識別用。予約IDはサーバーで処理し、Swiftの通常候補学習に渡さない。
5. **ライブ変換の自動確定**: 正本は共有Rustの `windows/crates/shared/src/conversion_policy.rs`（`find_auto_commit_live_conversion_snapshot_with_policy`）。設定 `general.auto_commit_live_conversion`（`off` / `standard` / `fast`、旧真偽値互換）に応じ、Zenzai の先頭トークン `top1 - top2` logit 差（`top_token_margins`）による低確信ホールド・高確信/句読点早期確定と、全体の読み長・確定長・残り長・安定ステップ数を組み合わせて自然な境界で先頭部分を確定する。確定後の続き入力では境界文脈を保持する継続変換（`ConversionContinuation.swift` / `AutomaticConversionContinuation.swift`）が働く。
6. **英字補完と共有学習**: Windows（`composition/english.rs`）・Android（`EnglishWordDictionary.kt`）ともに共有Rustの `windows/crates/shared/src/english_learning.rs` で単語学習（`english-learning.json`）を管理する。
7. **翻訳入力・コピー翻訳**: PLaMo 3 Translate（0.8B / Windowsは任意2B）を使用。Windowsはポート18092の翻訳用 `llama-server` 経由でリアルタイム入力翻訳（`Ctrl+T`）および選択・入力欄外コピー翻訳（`selection-ui` + `copy_translation.rs`）を提供する。

設定画面では、通常候補を作る処理と追加候補の提案を次の名前で表示する。

| 設定名 | 設定キー | 制御する処理 |
| --- | --- | --- |
| 予測変換（単語・フレーズ） | `predictive_conversion` | 入力中の読みから通常の予測候補を表示する |
| タイポ補正 | `experimental_typo_correction` | 通常変換内の補正と、未補正入力に対するモデルの補正候補生成をまとめて制御する |
| 文の先読み | `experimental_ai_assist` | 変換結果と文脈から文の続きを提案する |

補正と先読みの実行計画はWindows/Androidともに共通Rustが決める。無効な処理は要求を開始しない。
通常変換で補正済みの入力にはフォールバックを抑止する。先読みは補正後の読みを使う。
Windowsの「タイポ補正の推論エンジン」はフォールバック側の接続先を選ぶ（既定 `embedded`、検証用に `lm_studio`）。
Androidの「補正候補を自動で反映」は `auto_typo_correction` に対応する。
既存の設定キーと保存済みの真偽値を引き継ぐ。

### 設定・状態の保存先・ポート・ワーカー

- **ポート番号の整理**:
  - `18091` (`PREDICTION_SERVER_PORT`): メイン変換モデル（`zenz.gguf` / Nano v4.2）の常駐推論サーバー。先読み、Stage-2補正リランキング、再変換で利用する。
  - `18092` (`TRANSLATION_SERVER_PORT`): PLaMo 3 Translate 用推論サーバー（翻訳有効時または `translation_keep_alive` 有効時に launcher が起動）。
  - `18090`: 旧独立 Corrector サーバー用ポート（v0.2.3で廃止済み。`experimental_ai_engine_port` は互換設定としてのみ残存）。
- **設定画面のベンチマーク**: 共通候補生成は `azookey-server.exe`、モデル推論とAIアシスト評価は `keyboard-prior.exe` を専用ワーカーとして起動し、標準入出力で通信する。
- **バックエンドとGPU層数**: `zenzai.backend` は `vulkan`（既定）または `cpu`（CUDAバックエンドは廃止され、旧 `"cuda"` 設定は読み込み時に `vulkan` へ移行する）。`zenzai.gpu_layers` の既定値は99（全層）、CPUでは0層として扱う。詳細は [Windows推論のメモリ設定](investigations/windows-inference-memory.md) を参照。
- **Native打鍵補正ワーカー（`keyboard-prior.exe`）**: `ai_assist/keyboard_hybrid.rs` が `{app}\keyboard-prior.exe`（旧配置 `llama_cpu\keyboard-prior.exe` はインストーラーで削除）を起動・管理し、`Dictionary/Reverse/reverse-v2.bin`（AZR2）と現在選択中の変換モデルで補正読み候補を探索・採点する。Vulkanの初回推論はバックグラウンド起動時に済ませ、入力中の採点は350msまで待ち（超過応答は破棄）、通信切断や30秒応答なし時はワーカーを再起動する。
- **実設定・学習データの保存先**:
  - 実設定: `%APPDATA%\Azookey\settings.json`。`windows/deployment/settings.json` は配布時の初期コピー元であり、既存設定があれば上書きしない。
  - Windows のユーザー辞書は `%APPDATA%\Azookey\user-dictionary.json` に保存する。旧 `settings.json` の登録語は初回読み込み時に移行する。辞書画面での編集・読み込みは「保存」で反映する。
  - 変換用の日本語辞書は設定画面が `%LOCALAPPDATA%\ZeroConIME\dictionary\Dictionary` へ取得する。変換サーバーは `ZEROCON_DICTIONARY_DIR` → 実行ファイル横の `Dictionary\` → 取得済みキャッシュの順に解決する。
  - 補正学習: `%APPDATA%\Azookey\ai-assist-learning.json`（最大500件）、追加補正語彙は `ai-assist-vocabulary.json`。
  - 英字学習: `%APPDATA%\Azookey\english-learning.json`（Androidはアプリデータ配下 `Azookey/english-learning.json`）。
  - 変換学習・確定後予測: `%APPDATA%\Azookey\LearningMemory\`（`selection-overrides.json`、`post-composition-bigrams.json` 等）。選択履歴は左文脈と明示選択を記録し、1回の確定に含まれる文節間の遷移を確定後予測に利用する。
  - 先読みの確定履歴キャッシュ: Rustプロセス内に最大300件保持する。
- **学習の削除**: 共有エンジンに集約されている。`ForgetLearningCandidate` は候補IDだけで1件を消し、`GetLearningStats` はスコープ別件数を返し、`ResetLearningMemory` は `scopes`（`conversion` / `typo` / `english`）で選択削除する。
- **Named Pipe**: `shared/src/lib.rs` のPipe名は `\\.\pipe\LOCAL\azookey_{server,ui,launcher}_s<sessionId>`。古い固定名やセッション番号をハードコードしない。
- **配布初期設定** (`windows/deployment/settings.json`): Zenzai有効、Vulkan、`gpu_layers: 99`、モデル `standard_v4_2_iq4_emb8`、タイポ補正・AIアシスト・自動確定有効、翻訳機能無効、engine `embedded`。これは実行中のAppData設定の値を保証しない。

### Windowsのプライベートモード

TSFの入力欄が `IS_PRIVATE`、パスワード、PINの入力スコープを通知すると、自動でプライベートモードになる。状態はタスクバーのIMEアイコンのツールチップに表示する。プライベート入力では、周辺テキストの取得、学習の参照と保存、タイポ補正、先読み、翻訳、英字学習を停止し、基本のかな漢字変換を使う。

クライアントは入力スコープを周辺テキストの取得前に確認し、`SetContext` で空の文脈とプライベート指定を送る。変換サーバーが指定を返してから打鍵を送信する。再接続時も変換状態を復元する前に指定を送り直す。TSFの通知に対応するアプリで自動切り替えが働く。

## ユーザー辞書

Windows の「辞書」と Android の「学習とデータ › ユーザー辞書」から最大10,000件を登録できる。Android はアプリ内の `zerocon_user_data` に保存し、編集・読み込みの確定時に反映する。両 OS の登録項目は読み、単語、品詞。品詞と外部形式での名前は [共通カタログ](../data/user-dictionary-pos.json) を参照する。新規登録は一般名詞が既定で、旧データの品詞省略は固有名詞として引き継ぐ。共通 Swift エンジンは品詞ごとに接続 ID と意味 ID を設定し、定型文・ショートカットは読み全体に一致したときに候補に出す。

辞書ファイルでは読み・単語・品詞を移せる。他の IME のテキスト辞書を読み込むときは品詞名を ZeroConvert の品詞に変換する。例えば Google 日本語入力 / Mozc の「名詞サ変」と Microsoft IME の「さ変名詞」は、どちらも「サ変名詞」として取り込む。

| 形式 | 読み込み | 書き出し |
| --- | --- | --- |
| ZeroConvert JSON | 読み・単語・品詞をそのまま復元 | Windows と Android 間での移行、バックアップ用 |
| Google 日本語入力 / Mozc | 対応する品詞に変換 | Mozc の品詞名で書き出す UTF-8 のタブ区切りテキスト |
| Microsoft IME | 対応する品詞に変換 | Microsoft IME の品詞名で書き出す BOM 付き UTF-16LE のタブ区切りテキスト。組織は固有名詞、記号・顔文字は顔文字に変換 |
| ATOK | タブ区切りテキストから対応する品詞に変換 | — |

読み込み時は UTF-8、BOM 付き UTF-16、Shift_JIS（Windows-31J）を判別する。追加では同じ読み・単語の登録を残し、置き換えでは確認後に辞書全体を入れ替える。品詞を対応付けられない行は一般名詞に割り当て、抑制・制御・サジェスト専用の行は除外する。外部辞書のコメントは読み飛ばす。

## モデル作成とAndroid

- **モデル学習・配布**: 現行本番モデルは ZeroCon Model Nano / Standard v4.2（`IQ4_emb8` / `Q8`）で、正本カタログは `windows/deployment/model-catalog.json`。詳細は [model-assets.md](model-assets.md) を参照。
- **Android構成（`:rebuilt`）**: 2026-11-08 に旧 `:app` モジュールを廃止し、現在は `:rebuilt` のみがビルド対象。`ZeroConvertInputMethodService` → `KanaKanjiConverter` → `WindowsEngine` → JNI → `engine/rust`・`engine/swift`（Windowsと同一のSwift変換・Rustアシスト・共有ポリシー）・`engine/native`（llama.cpp + `keyboard_assist.cpp`）という共通エンジン構成で動作する。翻訳機能（PLaMo 3 Translate 0.8B）のみ `files/translation/` へオンデマンドダウンロードし、`assist_jni.cpp` の独立した Translation Runtime で推論する。詳細は [Android新構成](android-shared-engine.md)、[Android変換ロジック](android-conversion-logic.md)、[Android翻訳入力](investigations/android-translation-input.md) を参照。

## ビルド・実行・検証コマンド

以下のWindows開発コマンドの作業ディレクトリは **`windows/`**。定義元は同ディレクトリ内の `Makefile.toml`、`frontend/package.json`、ローカル手順は [development-workflow.md](development-workflow.md) と [local-installer.md](local-installer.md)。実際のルートCIは `.github/workflows/checks.yml`。

| 用途 | コマンド |
| --- | --- |
| フロント依存準備（`frontend/` 内） | `npm ci` |
| Swiftビルド | `swift build --package-path server-swift --scratch-path .build-modern -c release -Xcc -D_CRT_USE_C_COMPLEX_H -Xcxx -D_CRT_USE_C_COMPLEX_H` |
| Rust x64 | `cargo build --release --locked` |
| TSF DLL x86 | `cargo build -p azookey-windows --target=i686-pc-windows-msvc --release --locked` |
| 設定UI開発（`frontend/` 内） | `npm run tauri dev`（Viteのみは `npm run dev`） |
| 設定UIビルド（`frontend/` 内） | `npm run tauri:build` |
| Rustテスト | `cargo test --workspace --all-targets` |
| Swiftテスト | `swift test --package-path server-swift --scratch-path .build-modern -c release --build-system native --no-parallel -Xcc -D_CRT_USE_C_COMPLEX_H -Xcxx -D_CRT_USE_C_COMPLEX_H` |
| Rust書式チェック / lint | `cargo fmt --all -- --check` / `cargo clippy --workspace --all-targets -- -D warnings` |
| 設定UI型検査 / テスト（`frontend/` 内） | `npm run typecheck` / `npm test` |
| 候補スクロール・差分JSテスト | `node --test crates/ui/tests/candidate_scroller.test.cjs crates/ui/tests/correction_diff.test.cjs` |
| 逆引き辞書生成器テスト | `node --test scripts/generate-reverse-dictionary.test.mjs` |

Swift/Rustのビルドには `windows/scripts/modern-llama.ps1`（`Initialize-ModernLlama`）による `ZEROCON_CONVERTER_SOURCE` / `AZOOKEY_LLAMA_BACKEND_DIR` の準備、および `azookey-server.lib` の配置が必要になる。通常の試験反映はルートから **`Build-Trial.bat`**（増分ビルドと適用）、配布検証は **`Build-Package.bat`** → **`Apply-Trial.bat -PackageDirectory ...`** → **`Build-Installer.bat -PackageDirectory ...`** を使用する。

試験環境の操作（ワークスペースルートから）:

```powershell
powershell -ExecutionPolicy Bypass -File .\ZeroConvertIME-windows\Status-ZeroConvertIME.ps1
powershell -ExecutionPolicy Bypass -File .\ZeroConvertIME-windows\Start-ZeroConvertIME.ps1
powershell -ExecutionPolicy Bypass -File .\ZeroConvertIME-windows\Restart-ZeroConvertIME.ps1
```

初回登録は同ディレクトリの `Install-ZeroConvertIME.ps1`。TSF/スタートアップ登録・設定作成・起動を行うため、単なるビルド確認として実行しない。

Androidは **`Build-Android.bat`**（ルート）または **`android/`** 内で `./gradlew.bat :rebuilt:testDebugUnitTest :rebuilt:lintDebug :rebuilt:assembleDebug`。Web版の確認手順は [web/README.md](../web/README.md) を参照。

Python評価・学習はルートから `python scripts/run.py <スクリプト.py>` で実行する。

日常の開発では `Build-Trial.bat` が入力グループごとのソース指紋と前回の適用記録を比較し、必要なコンポーネントだけ増分ビルドして `.local/trial-updates/` の差分から適用する。変更がない場合はビルド・再起動を行わない。差分適用後の `trial-deployment.json` は `mode=incremental` となり、配布パッケージとの同一性を主張しない。配布前には全体の共通パッケージ作成・適用・確認を実施する。
