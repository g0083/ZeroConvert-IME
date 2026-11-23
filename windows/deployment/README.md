# ZeroConvert IME for Windows 配布パッケージ

このディレクトリは配布スクリプトのソースです。パッケージ作成・インストール時にバイナリと同じ配布先へコピーされます。以下の操作は、必要なバイナリが揃った配布フォルダで実行してください。

**ZeroConvert IME (Windows)** は、azooKey-Windows を基盤に、独自の **ZeroCon Model Nano v4.2**（ニューラルかな漢字変換・先読み・メインモデル統合タイポ補正）、GPU アクセラレーション（Vulkan / CPU）、ストリーミング自動確定、英字補完学習、ローカル翻訳入力（PLaMo 3 Translate、任意ダウンロード）を統合した Windows TSF 日本語入力システムです。

---

## 主な特徴

- **ZeroCon Model Nano / Standard v4.2 搭載**:
  - **Nano v4.2 IQ4_emb8**（既定・強制、約 18.7 MiB）、**Nano v4.2 Q8**、**Standard v4.2 IQ4_emb8**、**Standard v4.2 Q8** の 4 種類を選択できます。モデルはインストーラーに含まれず、`%LOCALAPPDATA%\ZeroConIME\models\` にキャッシュが無い場合は設定画面から GitHub リリースの固定アセットをダウンロードします（正確な仕様は `model-catalog.json` を参照）。
  - 辞書ラティス＋ニューラル変換により、文脈・同音異義語・日常会話・数値変換（`nam` 連携）を高精度かつ低遅延で実行します。
- **メインモデル統合のタイポ補正と文の先読み**:
  - 打鍵候補探索（`keyboard-prior.exe`）とメイン変換モデル（ポート `18091`）による 2 段リランキングで誤入力を補正し、同じモデルで文の先読みも提案します。
- **ストリーミング自動確定（長文の自動確定）**:
  - 16 文字以上の連続入力中、先頭側の変換が安定した文節境界を自動的に確定し、左文脈へ引き継ぎます。
- **英字単語補完・共有学習 & 翻訳入力（試験機能）**:
  - 英字モードでの前方一致補完と学習に加え、設定画面で有効化・ダウンロード（PLaMo 3 Translate 0.8B / 2B）することで `Ctrl+T` 翻訳入力・選択翻訳・コピー翻訳を利用できます。
- **GPU / CPU バックエンド**:
  - Vulkan（`llama_vulkan`、既定）および CPU（`llama_cpu`）の両ランタイムを同梱しています。

---

## クイックスタート（インストール・起動方法）

### 1. インストール（手動パッケージ展開時・管理者権限）

通常はリリース版インストーラー（`ZeroConvert-IME-setup-*.exe`）から導入できます。ZIP や配布フォルダから手動登録する場合は PowerShell で以下を実行します（管理者昇格プロンプトが表示されます）：

```powershell
powershell -ExecutionPolicy Bypass -File ".\Install-ZeroConvertIME.ps1"
```

これにより以下が自動設定されます：
1. `%APPDATA%\Azookey\settings.json` の初期構成（既存設定がある場合は上書きしません）
2. 64-bit (`azookey.dll`) および 32-bit (`azookey32.dll`) TSF IME コンポーネントの Windows 登録 (`ZeroConvert IME`)
3. Windows スタートアップへの `launcher.exe` 自動起動登録
4. バックグラウンド変換サーバー群（`launcher.exe` 経由）の起動

### 2. キーボードの切り替え
- **`Win + Space`** キーを押して、入力方式リストから **「日本語 - ZeroConvert IME」** を選択します。
- 入力モード切替ショートカット：
  - `半角/全角` キー
  - `Ctrl + Space`
  - `` Alt + ` ``

---

## 運用・管理スクリプト一覧

| スクリプト名 | 説明 |
|---|---|
| `Install-ZeroConvertIME.ps1` | TSF DLL 登録・初期設定・スタートアップ登録・ランチャー起動を一括実行 |
| `Register-StartupTask.bat` / `.ps1` | PC 起動時自動起動（タスクスケジューラ）を登録 |
| `Start-ZeroConvertIME.ps1` | `launcher.exe` を起動して変換サーバー・UI を開始 |
| `Stop-ZeroConvertIME.ps1` | ランチャー・サーバー・UI・設定画面プロセスを安全に停止 |
| `Restart-ZeroConvertIME.ps1` | プロセス再起動（`-ReRegisterDLLs` 指定で TSF DLL 再登録も実行） |
| `Restart-All-ZeroConvertIME.bat` / `.ps1` | 全関連プロセスの一括再起動 |
| `Status-ZeroConvertIME.ps1` | サーバー稼働状況・Named Pipe・モデル・TSF 登録状態を診断表示 |
| `Enable-DefaultIME.ps1` | 既定 IME 設定の補助 |
| `Fix-ZeroConvertIcons.ps1` | TSF プロファイルアイコンの修復補助 |
| `Remove-LegacyAzookeyTask.bat` | 旧 azooKey スタートアップタスクの削除補助 |
| `Uninstall-ZeroConvertIME.ps1` | TSF DLL 登録解除・スタートアップ削除・プロセス停止 |
| `frontend.exe` | ZeroConvert IME 設定アプリ（GUI）を起動 |

---

## ライセンスと謝辞

本ソフトウェアに含まれるオープンソースコンポーネントの著作権表示およびライセンス全文は [`THIRD_PARTY_LICENSES.md`](./THIRD_PARTY_LICENSES.md) をご参照ください。
