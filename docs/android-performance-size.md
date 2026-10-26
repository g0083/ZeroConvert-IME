# Android共通エンジンの性能・APK容量改善

2026-10-26。2.0.4-dev（versionCode 6）から2.0.5-dev（7）への変更。
WindowsのSwift/Rust正本を引き続き共用する。AzooKey/llama.cppの依存pin、モデル、候補順位・候補数・推論上限は変更しない。

## 実装

- 固定版llama.cppの公式ARM CPU variantsと実行時feature scoreを使う。基本ARMとDOTPROD/i8mm等の実装を同梱し、非対応命令を強制しない。
- Swift/JNIでllama/ggml共有ライブラリを共用する。バックエンドの配置とロードはAndroidホストが担当する。
- 要求世代をキューへ入れる前に取得する。不要になった要求は開始前に捨て、実行中はllamaのCPU abort callbackで中断する。
- 中断したKV/sessionは既存converter APIでリセットし、次の変換に壊れた状態を持ち越さない。
- Windows正本のパスヘルパーで一時ディレクトリ取得を遅延評価し、APPDATAが使えるときは実行しない。両OSで同じ最適化を使う。
- Windows正本のgetOptionsで絵文字辞書の解析済みTextReplacerを再利用する。約190KBの同梱ファイルを毎回読み直す処理を除く。Initialize/資産URL変更で再読み込みする。
- CPU別APK、nativeライブラリ圧縮、不要Swift runtimeの依存閉包による除外、逆引き辞書1本化を行う。モデル4種と旧Android辞書・既存履歴は保持する。
- CMakeの現在の出力一覧に基づいて動的ロード用CPUバックエンドを記録し、APK内でも依存閉包・モデルSHAを確認する。

Swiftの呼び出し周辺と共通の資産/パスヘルパー、JNI、Gradle/CMake、ビルドスクリプトに変更を限定した。
Windows更新後は従来と同じ `Build-Android.bat` で両ABIを作成し、指紋を照合する。
ARM64実機用は `rebuilt-arm64-v8a-debug.apk`、x86_64用は `rebuilt-x86_64-debug.apk`。
必要時のみ `-UniversalApk` で両CPUを含むAPKも追加する。

## APK容量

| 成果物 | バイト | 十進MB |
|---|---:|---:|
| 変更前2.0.4-dev・両ABI | 340,133,725 | 340.1 |
| 変更後2.0.5-dev・ARM64 | 191,760,207 | 191.8 |
| 変更後2.0.5-dev・x86_64 | 190,478,273 | 190.5 |
| 変更後2.0.5-dev・両ABI（任意） | 224,370,726 | 224.4 |

ARM64用APKは約43.6%削減。収録モデルは同一で、APKから読み出した4ファイルのSHA一致を確認した。
ライブラリ圧縮はAPK転送容量の削減であり、インストール時に展開される保存容量とは区別する。
ARM64 nativeの展開サイズは変更前85.7MB、変更後84.4MB。アプリ全体の使用量はモデル展開・利用履歴にも依存する。
大きなnativeの増分更新でAPKのZIPに古い領域が残ることも確認したため、コンパイルは増分のまま、
最終APKの梱包だけは毎回現在の入力から実行する。最終ARM64では不要領域を含む193.9MBから191.8MBへ減った。

## ソース・ビルド検査

- `scripts/build-android-shared-engine.ps1 -Abi arm64-v8a` / `-Abi x86_64` 成功。
- `scripts/build-android.ps1 -SkipNative -UniversalApk` 成功。JVM: app 163件、rebuilt 59件、失敗0。
- `:rebuilt:lintDebug`: 0 errors、28 warnings。
- 共通Rust: `cargo test --locked --manifest-path engine/rust/Cargo.toml --lib -- --test-threads=1`: 52成功、実モデル接続1除外。
- C++ホスト検査: `engine/native/tests/inference_control_test.cpp`。待機中の取消・実行中のCPU callback・次要求の回復を検査し成功。
- `check-shared-engine.mjs`、`check-android-native.py`、`check-android-apk.py` 成功。ARM64 30、x86_64 24ライブラリの依存閉包を確認。
- `check_repository.py`: 1,418ソース検査成功。
- Windows Swift: `swift test --package-path windows/server-swift --build-system native -c release --filter 'engineRuntimeDirectory|emojiTextReplacerCache'`: 6成功。絵文字の結果保持、同一資産再利用、資産切替・reset、パス遅延評価を確認。

C++検査はルートで以下を実行した（出力先はローカル検査用フォルダ）。

```powershell
clang++.exe '-std=c++17' '-O2' '-Iengine/native' '-Iandroid/app/src/main/cpp/llama.cpp/include' '-Iandroid/app/src/main/cpp/llama.cpp/ggml/include' engine/native/inference_control.cpp engine/native/tests/inference_control_test.cpp -o .local/android-performance-20261026/inference_control_test.exe
& .local/android-performance-20261026/inference_control_test.exe
```

署名互換性を確認済みのdebug APK。正式release署名・公開・pushは対象外。
Android検証時点ではWindows実IMEとGitへの保存は未実施だった。後続の実施範囲は末尾を参照。

## 実機比較

ユーザー指定のV2520A（SM8750、Android16/API36）で変更前後を比較した。変更前は2.0.4-dev、同じデータ・測定ループを両方に使う。
途中で診断用のduration項目を追加したが、測定対象や入力は変えない。
合成40例を3round、モデル・設定を維持し、初期化とwarmupを測定から分ける。
新しいホストはqueue/configure/native/decode時間も記録する。
ベンチマーク中に学習通知を行わず、個人の入力テキストは採取しない。
旧Kotlin/C++直接生成版との速度比を、この比較の速度比と混同しない。

初回測定（CPU backend/中断/梱包改善後、一時パス最適化前）は平均88.1ms→85.6ms、p95 160.8ms→127.2ms。
中央値77.6ms→79.2msで、平均の改善は小さく測定変動も考慮する。120測定のうち117件の出力が一致し、
同じ1例（3round）でWEB→Webの表記差があった。どちらもデータの期待値「ウェブ」とは不一致で、
その例での正解/不正解判定は変わらない。CPU実装の切替でビット単位の完全一致は保証しない。
中断→次の「東京」変換の回復3回も成功。モデルはNano Q4_K_M、SHA-256
`e0d2d20ff3da5dc71ba8e0fdc14c70701aec01b52afe75eba8e1f2ec0780b26e`。
初回の起動時にIME lifecycleから取消が入りwarmup前に停止した測定は無効として記録し、アプリ自身を前面にして再実行した。

一時パスのみの最適化後の測定はround平均222.6/96.8/90.7msで、更新直後の大きな変動もあった。
診断有効時の別測定は240.0msで、これを通常利用の改善値として扱わない。
絵文字キャッシュを含む最終APKの更新は一度INSTALL_FAILED_ABORTEDとなったが、ユーザーの再承認後に同じAPKで成功した。

### 最終結果

| 指標 | 変更前2.0.4-dev | 最終2.0.5-dev |
|---|---:|---:|
| 通常変換120測定・平均 | 88.118ms | 45.538ms |
| 中央値 | 77.621ms | 39.376ms |
| p95（nearest rank） | 160.787ms | 82.417ms |
| round平均（40例ずつ） | 79.424 / 86.230 / 98.700ms | 46.926 / 45.659 / 44.028ms |
| データの正解/許容値一致 | 31/40、各round同じ | 31/40、各round同じ |

平均は約48.3%短縮。入力待ち・画面表示までではなく、ZenzNative.convert呼び出し全体の時間。
117/120出力が同一、前述のWEB/Web差は最終版でも同じ1例に残る。モデルも同じNano Q4_K_M。
この測定だけで全ての入力・機種の精度や遅延を保証しない。

最終版の診断有効40例は平均44.453ms。準備段階は平均34.875msから1ms未満となり、
絵文字辞書の毎回の再解析を除いた効果を確認した（stageは整数ms単位）。native全体約43.65ms、
そのうちモデルdecode平均約11.02ms、queue約0.184ms、configure約0.081ms。

取消→次の「東京」変換の回復3回も成功。基本Instrumentationは22件・失敗0。
InputConnection、原打鍵・カーソル、部分確定、辞書・学習、再変換、実IME打鍵と確定、
QWERTY/フリックでの英字Enterを含む。設定画面の表示、更新後の版とモデル、
端末内base.apkと固定APKのSHA一致を確認した。アンインストール・データ消去・既定IME切替は行っていない。

最終ARM64 SHA-256: `cecbc599eceb705485ae986a1308bc689d9b58715e1649b6c9edf8113ed9da6a`。
ビルド後に再梱包/再署名せず、この同じAPKを端末で検査した。x86_64とuniversalは静的梱包検査まで。
Android検証時点ではWindows版のソース・Swiftテストまで更新し、Windows実IME・インストーラー・VMには反映していなかった。
既存のWindows release版メタデータ不一致（tauri.conf.json）は今回も検出し、変更していない。

配布候補: `dist/android/20261026-performance-size/`。SHA-256と詳細は同フォルダの記録を参照。

## 後続のWindows適用・Git保存（2026-10-26）

ユーザー承認後に `Build-Trial.bat` でWindows試験IMEへ増分ビルド・適用を実施した。
変更はSwift DLL 1ファイルで、メモ帳で原打鍵からの候補選択・確定、確定後のカーソル移動、
次の入力のEscape取消を確認した。Windowsの速度比較、インストーラー・VM検証は実施していない。
共通エンジン・Androidホストと今回の最適化はGitコミット対象とし、無関係な既存のWindows変更は保持する。
適用物の識別と検証範囲は [Windows適用記録](windows-shared-performance-validation.md) を参照。
