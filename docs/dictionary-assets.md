# 日本語辞書の実行時取得

変換に使う日本語辞書（azooKey標準辞書＋ZeroConvert追加辞書）は、WindowsインストーラーにもAndroid APKにも同梱しない。初回起動時に設定画面が固定アセットを取得して端末へ展開し、揃っていれば再取得しない。追加辞書の中身と選出根拠は [Windows追加辞書の生成と比較](investigations/additional-dictionary-plan.md) を参照する。

## 配布アセット

| 項目 | 値 |
| --- | --- |
| 配布元 | GitHub リリース `dictionary-v1` の固定アセット（`https://github.com/g0083/ZeroConvert-IME/releases/download/dictionary-v1/zerocon-dictionary-azookey-expanded.zip`） |
| ファイル名 | `zerocon-dictionary-azookey-expanded.zip` |
| サイズ | 23,357,023 bytes（展開後 55,094,243 bytes / 3,621ファイル） |
| 収録内容 | `Dictionary/louds`・`Dictionary/Reverse/reverse-v2.bin`（629,045組）・`cb`・`p`・`mm.binary`・`AdditionalDictionaryNotices` |
| 検証 | SHA-256（正本は下記カタログ）＋ファイル数＋`louds/charID.chid` の存在 |

正本は [windows/deployment/dictionary-catalog.json](../windows/deployment/dictionary-catalog.json)。zip は Git 管理外で、カタログだけをコミットする。生成は次のとおり。

```powershell
python scripts/run.py scripts/build-dictionary-asset.py `
    --dictionary windows/target/trial-dictionary/Dictionary `
    --output artifacts/build/dictionary-asset/zerocon-dictionary-azookey-expanded.zip
```

入力は `python scripts/run.py scripts/prepare_dictionary.py --output <dir>` が作る検証済み辞書一式（azooKey固定リビジョン＋`data/dictionary/additional-louds.zip`＋逆引き辞書）。スクリプトは zip を作り直したうえで、サイズ・SHA-256・ファイル数・逆引き組数をカタログへ書き戻す。辞書を更新したら、カタログの反映先（下記）も同時に更新する。

## 保存先と解決順

- **Windows**: `%LOCALAPPDATA%\ZeroConIME\dictionary\Dictionary\`。同じ階層の `.complete` を展開完了の印とし、これが無い間はキャッシュとして扱わない。
- **Android**: `filesDir/dictionary/Dictionary/`。同じ `.complete` 判定を使う。
- 変換サーバー（Swift）の解決順は `ZEROCON_DICTIONARY_DIR` → 実行ファイル横の `Dictionary\` → 取得済みキャッシュ。
  - `ZEROCON_DICTIONARY_DIR` は Android の JNI（`nativeSetDictionaryRoot`）だけが設定する。
  - 実行ファイル横を優先するのは `Build-Trial.bat -DictionaryOnly` の辞書比較をそのまま使うため。配布版はインストーラーが `Dictionary\` を同梱せず、更新時に削除するので、実際にはキャッシュだけが存在する。

## 取得の流れ

1. 設定画面は起動時に変換モデル・日本語辞書・英字辞書の状態をまとめて問い合わせる（Windows `setup_assets_status`、Android `MainActivity`）。
2. 不足があれば1つの確認ダイアログに3点を並べる。「あとで」を選ぶとダイアログだけ閉じ、設定画面はダウンロードボタンだけの画面（ゲート）になり、準備が終わるまで設定と入力を使えない。
3. ダウンロードは変換モデル → 日本語辞書 → 英字辞書の順に、不足分だけ実行する。
4. 展開は「ステージング → 既存の置き換え → `.complete`」の順。途中で失敗した辞書はキャッシュとして採用しない。
5. 辞書を取得したら変換エンジンへ読み直させる。Windows は変換サーバーを再起動し、Android は `setDictionary` op で辞書と再変換辞書を作り直す。
6. Android は順序が重要で、**変換モデルの取得直後に `ZenzNative.init` でエンジンを初期化してから**辞書・英字辞書を取得する。英字辞書の抽出（`englishDictionaryImport`）と `lookupDict` は共有エンジンに op を送るため、未初期化だと `-1`／空が返る。辞書の取得後は `WindowsEngine.reloadDictionary()` に加えて `ZenzNative.refreshDictionary()`（旧 LOUDS 経路）と `ReadingCheck.reload()` を呼ぶ。

実装は次のファイルが担当する。

| 層 | ファイル |
| --- | --- |
| 共通（配置・URL・SHA-256） | `windows/crates/shared/src/runtime_assets.rs` |
| Windows 取得・展開 | `windows/frontend/src-tauri/src/dictionary.rs` |
| Windows 起動ゲート | `windows/frontend/src/components/setup-provider.tsx`、`windows/frontend/src/lib/setup-assets.ts` |
| Android 取得・展開 | `android/app/src/main/java/com/zeroconvert/ime/model/DictionaryDownloadManager.kt` |
| Android 起動ゲート・差し替え | `android/app/src/main/java/com/zeroconvert/ime/MainActivity.kt`、`engine/WindowsEngine.kt`、`engine/swift/AndroidBridge.swift` |
| Android 旧 LOUDS 経路 | `android/app/src/main/java/com/zeroconvert/ime/ZenzNative.kt`（`lookupDict` 用に取得済み辞書を `nativeInitDict` へ渡す）、`java/com/zeroconvert/ime/assist/ReadingCheck.kt`（逆引き辞書を取得済み `Reverse/reverse-v2.bin` から読む） |

Android の `DictionaryDownloadManager` は展開直後に、誤入力補正の辞書照合が使う旧 LOUDS 経路向けの `cb_packed.binary` を `cb/<id>.binary`（Int32キー・Float値の列、先頭キー `-1` が既定値）から生成する。アセットには含めないため、辞書を更新しても端末側で自動的に作り直される。

## 配布物からの除外

- Windows: `windows/scripts/prepare-distribution.ps1` は辞書を payload へ入れない。`distribution.psm1` の `Read-PreparedPackage` と `verify_release_artifact.ps1` は `Dictionary/` が混ざっていれば失敗する。インストーラーは `[InstallDelete]` で旧版の `{app}\Dictionary` を削除する。
- Windows のアンインストーラーは `[UninstallDelete]` で `{localappdata}\ZeroConIME`（変換モデル・日本語辞書・英字辞書の取得物と `.part`）を削除する。設定と学習履歴は `{userappdata}\Azookey` 側にあり、そちらは残す。インストール先だけを消すと取得物が残り続けるため、`distribution.tests.ps1` がこの設定の有無を検査し、`verify_release_artifact.ps1 -SilentInstall` はアンインストール後に取得物が消えたことも確認する。
- Android: `android/rebuilt/build.gradle.kts` の `sharedEngineAssets` が `shared-engine/Dictionary/**` を除外し、`verifyConversionModels` と `scripts/check-android-apk.py` が APK 内の辞書を検査する。Kotlin 側の固定値（ファイル名・サイズ・SHA-256・ファイル数・タグ）はカタログと一致していなければビルドが止まる。

## ライセンス

辞書ZIPの `Dictionary/AdditionalDictionaryNotices/` に、SudachiDictの `LEGAL` とApache License 2.0全文、WikidataのCC0 1.0と出典を収録する。同じ文書をWindowsのインストール先の `AdditionalDictionaryNotices/` とAndroidの `assets/licenses/AdditionalDictionaryNotices/` にも同梱する。アプリのライセンス画面は同梱文書を表示する。 [Windows配布用](../windows/deployment/THIRD_PARTY_LICENSES.md) · [Android](../android/THIRD_PARTY_LICENSES.md)

## 検証

- `cargo test -p shared` と `cargo test -p frontend --manifest-path windows/frontend/src-tauri/Cargo.toml dictionary` が解決順・パス検証・展開と置き換え・完了マーカーを確認する。
- 実アセットの確認は環境変数で有効化する。`ZEROCON_DICTIONARY_TEST_ZIP=<zip>` で展開後のツリーを、`ZEROCON_DICTIONARY_TEST_NETWORK=1` で公開済みURLからの取得とサイズ検証を実行する（通常のテストではネットワークを使わない）。カタログを更新したら両方を流し、公開アセットが定数と一致することを確かめる。
- `android/rebuilt/build.gradle.kts` の `verifyConversionModels`、`scripts/check-android-apk.py`、`windows/scripts/tests/distribution.tests.ps1` がカタログ整合と無同梱を確認する。
- `android/app/src/test/java/com/zeroconvert/ime/DictionaryConnectionCostTest.kt` が `cb_packed.binary` の詰め替え（欠番行は既定値、little-endian の float）を確認する。
- 端末内テストは `PortInstrumentationRunner` の `native_dictionary_morphology`・`reverse_dictionary_and_privacy`・`native_bounded_keyboard_rewrites` が、取得済み辞書で形態素解析と逆引きが動くことを確認する。
- 実機確認では、クリーンインストールでダウンロードが自動で始まること、取得済みなら再取得しないこと、オフライン時は失敗表示と再試行だけが出ること、旧版からの更新で辞書が残らないことを見る。
