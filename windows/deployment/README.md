# ZeroCon IME for Windows (v3.2)

このディレクトリは配布スクリプトのソースです。ビルド時にバイナリと同じ配布先へコピーされます。以下の操作は、必要なバイナリが揃った配布フォルダで実行してください。

**ZeroCon IME (Windows)** は、Android 版で実証済みの **ZeroCon AI**（`ZeroCon Model v3.2` ニューラルかな漢字変換モデル）と、GPU アクセラレーション（NVIDIA CUDA / Vulkan）およびストリーミング自動確定（長文の自動確定）を統合した次世代 Windows TSF 日本語入力システムです。

---

## 主な特徴

- **ZeroCon AI ニューラル変換モデル搭載**:
  - `ZeroCon Model v3.2 Fast (IQ4_XS, 52.6 MB)` / `ZeroCon Model v3.2 Standard (Q4_K_M, 61.3 MB)` を同梱。
  - 文脈・同音異義語・長文かな漢字変換を直接ニューラル推論＋辞書ラティス融合により高精度（~90%）かつ高速に実行。
- **ストリーミング自動確定（長文の自動確定）**:
  - 16 文字以上の連続入力中、先頭側の漢字変換が直近 5 打鍵連続で安定した文節境界を自動的に確定し、左文脈（Left Context）へ引き継ぎます。スペースキーや Enter キーを押さずに長文をノンストップで快適に入力可能です。
- **GPU フルオフロード対応**:
  - NVIDIA CUDA 12 (`llama_cuda`) / Vulkan (`llama_vulkan`) による全レイヤー GPU オフロード推論に対応。

---

## クイックスタート（インストール・起動方法）

### 1. インストール（初回のみ・管理者権限）
PowerShell で以下を実行します（自動的に管理者昇格プロンプトが表示されます）：

```powershell
powershell -ExecutionPolicy Bypass -File ".\Install-ZeroConvertIME.ps1"
```

これにより以下が自動設定されます：
1. `%APPDATA%\Azookey\settings.json` の初期構成（`ZeroCon AI` 有効化）
2. 64-bit (`azookey.dll`) および 32-bit (`azookey32.dll`) TSF IME コンポーネントの Windows 登録 (`ZeroCon IME`)
3. Windows スタートアップへの `launcher.exe` 自動起動登録
4. バックグラウンド変換サーバー (`azookey-server.exe`) の起動

### 2. キーボードの切り替え
- **`Win + Space`** キーを押して、入力方式リストから **「日本語 - ZeroCon IME」** を選択します。
- 入力モード切替ショートカット：
  - `半角/全角` キー
  - `Ctrl + Space`
  - `Alt + `` ` ``

---

## 運用・管理スクリプト一覧

| スクリプト名 | 説明 |
|---|---|
| `Install-ZeroConvertIME.ps1` | TSF DLL 登録・初期設定・スタートアップ登録（タスク＋レジストリ）・サーバー起動を一括実行 |
| `Register-StartupTask.bat` | PC 起動時自動起動（タスクスケジューラ＋HKCU Run）をワンクリックで登録 |
| `Start-ZeroConvertIME.ps1` | バックグラウンド変換サーバー (`azookey-server.exe`) を起動 |
| `Stop-ZeroConvertIME.ps1` | サーバー・UI オーバーレイ・設定画面プロセスを安全に停止 |
| `Restart-ZeroConvertIME.ps1` | サーバー再起動（`-ReRegisterDLLs` 指定で TSF DLL 再登録も実行） |
| `Status-ZeroConvertIME.ps1` | サーバー稼働状況・メモリ使用量・Named Pipe・モデル・TSF 登録状態を診断表示 |
| `Uninstall-ZeroConvertIME.ps1` | TSF DLL 登録解除・スタートアップ削除・プロセス停止 |
| `frontend.exe` | ZeroCon IME 設定アプリ（GUI）を起動 |

---

## ライセンスと謝辞

本ソフトウェアに含まれるオープンソースコンポーネントの著作権表示およびライセンス全文は [`THIRD_PARTY_LICENSES.md`](./THIRD_PARTY_LICENSES.md) をご参照ください。
