# ZeroConvert-IME エージェントガイド

## 目的・現在地

- Windows版 azooKey を基盤に、zenz系の独自 ZeroCon Model を利用し、変換操作を減らす日本語IMEを開発する（開発方針はユーザー指定）。
- コードにはライブ変換、安定した先頭部分の自動確定、タイポ補正、先読み、設定UIが存在する。ユーザー報告ではタイポ補正の不動作・先読み精度が課題。実装の存在と動作品質を区別する。
- Windowsが開発の基準。Android実装とモデル学習・評価も同居するが、Windowsの修正時に一括探索しない。

## 最初に見る場所

- ルートのGit管理先は `https://github.com/g0083/ZeroConvert-IME.git`（`origin`）。Windowsソースは `windows/`、Androidは `android/`。ルートのGitで管理する。
- `windows/` の作業には、その配下の [AGENTS.md](windows/AGENTS.md) と必要な開発手順も適用する。
- 学習は `training/`、評価は `benchmarks/`、共有データは `data/`。Pythonは `python scripts/run.py <対象.py> [引数]` でルート基準のパス・importを揃える。
- モデルは `artifacts/models/`、学習出力は `artifacts/checkpoints/`、評価結果・ログは `artifacts/results/` / `artifacts/logs/`。いずれもGit対象外。
- `scratch/` は旧チェックアウト・実験保管先でGit対象外。`scratch/batao_src/` は移行前の保全用であり、今後の編集先ではない。ローカル履歴バックアップは `.local/migration-backup/`。既存の実行環境 `ZeroConvertIME-windows/` もGit対象外で保持する。
- 外部依存はルートの `.gitmodules` で固定。Android llama.cppの独自変更は `patches/llama.cpp/` に保存し、`scripts/setup-dependencies.ps1` で適用する。
- 構造・データフロー・設定・コマンドは [docs/architecture.md](docs/architecture.md)、症状別の探索先・詳細なファイル一覧は [docs/file-map.md](docs/file-map.md)。

## 中核ファイル（パスは Windows ソースルートから）

| ファイル | 責務 |
| --- | --- |
| `crates/client/src/engine/composition.rs` | 入力状態、ライブ変換、自動確定、アシスト表示・採用 |
| `crates/server/src/main.rs` | 変換RPC、Swift FFI、アシスト候補追加、確定通知 |
| `crates/server/src/ai_assist/mod.rs` | Rust側のタイポ補正・先読み生成 |
| `server-swift/Sources/azookey-server/azookey_server.swift` | 辞書・Zenzai変換、文脈・設定適用 |
| `crates/shared/src/lib.rs` | 共通設定、候補ID、セッション別IPC名 |

## 変更の原則

- Rustは既存の `rustfmt`、各言語は周辺の命名・構造に合わせる。入力・TSFコールバック・候補更新経路に、待機・同期I/O・無制限の処理を増やさない。
- 入力順序、読み、未確定ローマ字、文字数、候補ID、文脈を別々に扱う。タイポ補正と先読みの優先順、通常候補の非破壊性を保つ。
- 設定変更はRust定義、TypeScript定義・既定値、設定画面、Swift受け渡し、配布初期設定の整合を確認する。
- 実行バイナリとソースが一致するとは限らない。モデル名・設定・ポート・実行パスを確認してから不具合を判断する。
- 配布DLL/EXE、モデル重み、ビルド出力、依存取得物を直接修正しない。生成元と例外的な調査対象は詳細文書を参照。
- 既存の `patch_*.py` や実験スクリプトを修正手順とみなして無条件に実行しない。

## タスク完了条件

- 変更した層に対応するビルド・テスト・lintを実施し、コマンドと結果、未実施なら理由を報告する。文書のみなら参照パス・内容・変更範囲を確認する。
- IME動作の修正は、問題の打鍵・削除・カーソル移動・確定/取消を順に再現し、対応する回帰テストとWindows上の確認を行う。モデル単体の正答率で実IMEの成功を代用しない。
- 精度の変更は、タイポの取りこぼし・正常入力の誤補正・先読みの妥当性・遅延を比較し、モデルと設定を記録する。
- 無関係な既存変更・ユーザー設定・学習履歴を保持する。探索先や実行手順が変われば該当文書を更新する。

## 目指す状態

ユーザーの意図する、変換操作を必要としない快適なWindows IMEへ近づける。現在の重点はタイポ補正の安定動作と先読み精度の改善。達成済みの品質基準や数値目標は、この文書では設定しない。
