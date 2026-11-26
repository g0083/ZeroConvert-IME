# 日本語辞書の実行時取得

変換に使う日本語辞書（azooKey標準辞書＋ZeroConvert追加辞書）は、WindowsインストーラーにもAndroid APKにも同梱しない。初回起動時に設定画面が固定アセットを取得して端末へ展開し、揃っていれば再取得しない。追加辞書の中身と選出根拠は [Windows追加辞書の生成と比較](investigations/additional-dictionary-plan.md) を参照する。

## 配布アセット

| 項目 | 値 |
| --- | --- |
| 取得先 | GitHub Releasesの `dictionary-v2` 固定アセット |
| ファイル名 | `zerocon-dictionary-azookey-expanded.zip` |
| サイズ | 60,975,173 bytes（展開後197,378,811 bytes、5,230ファイル） |
| 収録内容 | `Dictionary/louds`・`Dictionary/Reverse/reverse-v2.bin`（1,618,968組）・`cb`・`p`・`mm.binary`・`AdditionalDictionaryNotices` |
| 検証 | SHA-256（正本は下記カタログ）＋ファイル数＋`louds/charID.chid` の存在 |

公開版の正本は [windows/deployment/dictionary-catalog.json](../windows/deployment/dictionary-catalog.json)。ZIPはGit管理外で、カタログをコミットする。公開前のZIP生成は [町字辞書の生成手順](#全国の町名大字丁目小字の更新) のように `--catalog-output` で作業用のカタログを指定する。

入力は `python scripts/run.py scripts/prepare_dictionary.py --output <dir>` が作る検証済み辞書一式（azooKey固定リビジョン＋`data/dictionary/additional-louds.zip`＋逆引き辞書）。スクリプトは zip を作り直したうえで、サイズ・SHA-256・ファイル数・逆引き組数をカタログへ書き戻す。配布用アセットを更新するときは、カタログの反映先（下記）も同時に更新する。

## 都道府県・市区町村名の更新

デジタル庁の全国市区町村マスターから、都道府県名、市区町村名、政令指定都市の区名と市名付き区名を読みと組にして取り込む。基準日に有効な行を使い、既存の表記と読みの組はそのまま使う。新しい組には固有名詞のスコアを付ける。同じ読みの候補がある場合、新しい組の辞書スコアは既存候補の最高スコアを下回る。 [データの出典と利用条件](../data/dictionary/AdditionalDictionaryNotices/AddressBaseRegistry.txt)

2026年11月26日の取り込みに使った `mt_city_all.csv.zip` の SHA-256 は `c719e7394489907c6192f58837d7f61274c05feb72ebec2543995d0d6cb08b5e`。基準にした629,045組版の生成先には `Dictionary/`、`entries.json`、`additional-dictionary.json` が揃っている必要がある。Swiftのビルダーと依存ライブラリの用意は [Windows追加辞書の生成と比較](investigations/additional-dictionary-plan.md) を参照する。

```powershell
python scripts/run.py windows/scripts/add_municipalities.py `
    --previous artifacts/build/additional-dictionary/trial-20260723-plus100000 `
    --archive artifacts/downloads/address-base-registry/mt_city_all.csv.zip `
    --as-of 2026-11-26 --builder $builder `
    --output artifacts/build/additional-dictionary/trial-municipalities-20261126
```

市区町村までの辞書は629,503組で、次の町字辞書の土台になる。

## 全国の町名・大字・丁目・小字の更新

全国町字マスターの基準日に有効な全行から、町名・大字、丁目、小字を取り込む。町名と丁目、小字の組み合わせも、エンジンで一語として扱える20文字以内の読みなら登録する。数字のある読みは数字のまま打てる形と、「１１丁目」を「じゅういっちょうめ」と打てる形を収録する。読みが空欄の場合は同じ自治体・同じ町字名の公的データを照合し、読みが一意のものを補う。表記がかなの地名はかなから読みを作る。登録できなかった町字は自治体コードと町字ID付きのTSVに出力する。 [データの出典と利用条件](../data/dictionary/AdditionalDictionaryNotices/AddressBaseRegistry.txt)

2026年11月26日の生成では、全国727,535行から989,465組を追加し、固定辞書は1,618,968組となった。使用した入力のSHA-256は次のとおり。

| 入力 | SHA-256 |
| --- | --- |
| 全国町字マスター `mt_town_all.csv.zip` | `a04309d2dfd4211d57402441bde7a60b7b1aa18da53f2ab51d9f59a0b7ac4e9f` |
| 日本郵便 `utf_ken_all.zip` | `db92fcf624bb7280e6f51900aebe03adeafbd168796e59412dbc22a80abb85b9` |

```powershell
$townTrial = 'artifacts/build/additional-dictionary/trial-town-names-20261126'
$asset = 'artifacts/build/dictionary-asset/town-names-20261126'
python scripts/run.py windows/scripts/add_town_names.py `
    --previous artifacts/build/additional-dictionary/trial-municipalities-20261126 `
    --archive artifacts/downloads/address-base-registry/mt_town_all.csv.zip `
    --postal artifacts/downloads/address-base-registry/utf_ken_all.zip `
    --as-of 2026-11-26 --builder $builder --output $townTrial `
    --unresolved artifacts/results/address-dictionary-20261126/unresolved-town-names.tsv
python scripts/run.py scripts/prepare_dictionary.py --freeze-trial $townTrial
python scripts/run.py scripts/prepare_dictionary.py --output "$asset/Dictionary"
python scripts/run.py scripts/build-dictionary-asset.py `
    --dictionary "$asset/Dictionary" `
    --output "$asset/zerocon-dictionary-azookey-expanded.zip" `
    --catalog-output "$asset/dictionary-catalog.json" --release-tag dictionary-v2
```

Windowsの試験IMEには `Build-Trial.bat -DictionaryOnly` で適用できる。WindowsとAndroidの取得設定が同じ `dictionary-v2` を参照する。配布版への適用時は、共通ZIPを先に公開して取得設定と照合する。

## 保存先と解決順

- **Windows**は `%LOCALAPPDATA%\ZeroConIME\dictionary\Dictionary\` に展開する。設定画面は同じ階層の `.complete` に記録されたSHA-256を照合し、旧版なら現在の辞書を取得する。変換サーバーは、再取得中も展開済みの旧辞書を使用できる。
- **Android**は `filesDir/dictionary/Dictionary/` に展開する。`.complete` のSHA-256が取得設定と一致すると準備完了になる。補正・先読み用の逆引き索引はファイルのままメモリマップする。
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
