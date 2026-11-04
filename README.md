# ZeroConvert-IME

Windows版azooKeyを基盤に、zenz系のZeroCon Modelで変換操作を減らす日本語IMEを開発しています。現在はタイポ補正の安定動作と先読み精度の改善に取り組んでいます。Android実装とモデル学習・評価コードも同じリポジトリで管理します。

## 📥 すぐに試す（最新リリース）

最新版のインストーラーおよびAPKは、**[GitHub Releases (v0.2.0)](https://github.com/g0083/ZeroConvert-IME/releases/latest)** からダウンロードしてすぐにお試しいただけます。

| プラットフォーム | 配布ファイル | 説明 |
|---|---|---|
| **Windows 10 / 11** | [**ZeroConvert-IME-setup-v0.2.0.exe**](https://github.com/g0083/ZeroConvert-IME/releases/download/v0.2.0/ZeroConvert-IME-setup-v0.2.0.exe) | インストーラーを実行するだけでモデル・エンジン一式を自動セットアップ |
| **Android (全CPU共通)** | [**ZeroConvert-IME-v0.2.0-universal-debug.apk**](https://github.com/g0083/ZeroConvert-IME/releases/download/v0.2.0/ZeroConvert-IME-v0.2.0-universal-debug.apk) | ARM64 / x86_64 両対応の共通エンジン版APK |
| **Android (実機推奨)** | [**ZeroConvert-IME-v0.2.0-arm64-debug.apk**](https://github.com/g0083/ZeroConvert-IME/releases/download/v0.2.0/ZeroConvert-IME-v0.2.0-arm64-debug.apk) | 一般的なスマートフォン実機向け軽量APK |

> 詳しい改善点、ベンチマーク詳細、モデル学習の解説は [リリースノート (v0.2.0)](https://github.com/g0083/ZeroConvert-IME/releases/tag/v0.2.0) をご覧ください。

## 変換操作を減らす、独自のZeroCon Model Nano v4

ZeroCon Modelは、azooKeyの日本語変換モデル **zenz-v3.2-xsmall** を出発点に、文脈に応じた漢字選択、専門語・四字熟語、複合文節、英数字や空白の保持を強化した派生モデルです。Windows・Androidともに通常変換モデルを次の2種類に統一しています。

| モデル | サイズ | 特徴 |
|---|---:|---|
| **Nano v4 IQ4_emb8**（既定） | **18.7 MiB** | 内部をIQ4_NL、入力埋め込み・出力をQ8_0にした省メモリ版 |
| **Nano v4 Q8** | **27.7 MiB** | Q8_0で学習成果を保持した高精度版 |

6層・約26.7Mパラメータの構造を保ちながら、rank-64 DoRA、Standard v3教師と元Nanoの二重アンカー蒸留、教師の誤答ピークを正解へ入れ替える補正KL、文節間の誤った自己コピーを抑えるマージン学習を組み合わせました。さらにEOSの不要な句点・タグ生成を抑え、推論器と実IMEで異なる空白の符号化を両方学習しています。学習時のadapterは重みに統合するため、推論時に追加の層やadapter実行は必要ありません。

### ベースzenzからどこまで進んだか

同じ開発評価480問（基本・文脈230問＋高難度250問）のモデル単体評価です。

| モデル | 230問 | 高難度250問 | 総合 |
|---|---:|---:|---:|
| ベース zenz-v3.2-xsmall Q4_K_M | 181 | 172 | 353/480（73.5%） |
| **ZeroCon Nano v4 IQ4_emb8** | **206** | **239** | **445/480（92.7%）** |
| **ZeroCon Nano v4 Q8** | **206** | **245** | **451/480（94.0%）** |

ベースQ4との比較でIQ4_emb8は**92問、19.2ポイント改善**。量子化形式が異なる比較であることに留意してください。F16同士でもベース362問からNano v4の451問へ改善しています。評価と学習の正規化キーを分離し、学習817行・検証85行に対して評価セットとのキー重複0件を確認しています。ただし、これらは繰り返し使う開発用問題であり、未知入力や実IME全体の成功率を保証する値ではありません。

CPUのみ・4スレッドのi5-14500HXでは、IQ4_emb8は平均38.7ms、Q8は40.3msで全文の変換出力を完了しました。CPUでの正解数はそれぞれ445/480、454/480。これはPCでのモデル単体測定で、Android端末の速度や打鍵から画面反映までの遅延とは区別します。詳細は [Nano v4の学習・評価](reports/models/zerocon_model_nano_v3_experiment_report.md) と [CPU比較](reports/models/nano-standard-v4-cpu-comparison.md) を参照してください。

## モデルだけでなく、入力から確定までを改良

ZeroConvert-IMEは、重みを差し替えるだけのIMEではありません。azooKeyの辞書・Zenzaiを基盤に、入力中の状態管理、候補統合、補正の採用・取消、確定学習までを組み合わせています。

- **ライブ変換とストリーミング確定**：入力に追従して変換し、長文では先頭の変換が安定した部分を自然な境界で自動確定します。
- **辞書とニューラル評価の統合**：辞書候補・読み・文脈・学習履歴を使って候補を組み立てます。かな漢字変換、タイポ補正、先読みを別の役割として扱い、通常候補を保持します。
- **原打鍵からのタイポ補正**：ローマ字列、読み、補正後表記を区別し、固定規則・学習済み補正・辞書探索・専用モデルを組み合わせます。隣接キーの誤りも辞書候補の探索に使います。
- **採用できる先読み**：文脈や変換済み表記を使って続きを提案し、補正を優先して表示します。提案を採用する操作と通常の確定学習を分けています。
- **非同期結果の整合**：削除・追加入力・カーソル移動・入力先変更をまたぐ古い応答を抑止し、読み・文字数・候補IDを照合して反映します。
- **AndroidでもWindowsの中核を共有**：現行の `:rebuilt` はWindowsのSwift変換エンジンとRustアシストを直接コンパイルし、自動確定の共通方針も利用します。Androidの入力・キーボード・ライフサイクルは専用ホストが担います。

通常変換の選択肢は上記2種類です。タイポ補正専用のCorrector（corecctor）とWindowsのKeyboard Priorは別用途として維持しています。補正の取りこぼし・正常入力への誤補正・先読みの妥当性は引き続き改善中です。内部構成は [Windows変換ロジック](docs/windows-conversion-logic.md)、[Android共通エンジン](docs/android-shared-engine.md)、モデル配置は [モデル資産ガイド](docs/model-assets.md) を参照してください。

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
