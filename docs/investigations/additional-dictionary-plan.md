# Windows追加辞書の生成と比較

**状態　実装済み。629,045組をWindowsとAndroidの初回取得用アセットに採用。** 現在の固定辞書と配布状況は [辞書の取得と更新](../dictionary-assets.md) を参照。

SudachiDict Fullの名詞とWikidataの固有名詞を、azooKeyの標準辞書へ追加する。Windowsの試験IMEでは、同じバイナリとZeroCon Modelで標準辞書と追加辞書を比較できる。 [Windows開発・反映手順](../development-workflow.md)

## 入力を固定する

- SudachiDict Fullの公式V0ソースから同じ版の `small_lex.zip`、`core_lex.zip`、`notcore_lex.zip` を取得する。対応するタグから `LEGAL` と `LICENSE-2.0.txt` も取得し、同じディレクトリに置く。初回は20260723版を使った。 [辞書とソースの配布元](https://github.com/WorksApplications/SudachiDict#download)
- Wikidataの採用候補を [QIDリスト](../../windows/data/additional-dictionary-wikidata.json) に並べる。各QIDの `https://www.wikidata.org/wiki/Special:EntityData/<QID>.json` を取得し、`<QID>.json` として同じディレクトリに置く。別名と読みの対応を公式資料で確認したときは、該当する項目に `surface`、`reading`、`evidence` を追加できる。`evidence` は確認に使った資料のURLを記録する。 [Wikidataのデータ取得ガイド](https://www.wikidata.org/wiki/Wikidata:Data_access)

入力をリポジトリルートから取得する。更新時は入力先と版番号を替え、既に固定した版のファイルを保持する。

```powershell
$source = 'artifacts/downloads/additional-dictionary'
$version = '20260723'
New-Item -ItemType Directory -Force "$source/$version", "$source/wikidata" | Out-Null
foreach ($name in 'small_lex', 'core_lex', 'notcore_lex') {
    Invoke-WebRequest "https://sudachi.s3.ap-northeast-1.amazonaws.com/sudachidict-raw/$version/$name.zip" -OutFile "$source/$version/$name.zip" -ErrorAction Stop
}
foreach ($name in 'LEGAL', 'LICENSE-2.0.txt') {
    Invoke-WebRequest "https://raw.githubusercontent.com/WorksApplications/SudachiDict/v$version/$name" -OutFile "$source/$version/$name" -ErrorAction Stop
}
$selection = Get-Content windows/data/additional-dictionary-wikidata.json -Raw | ConvertFrom-Json
foreach ($item in $selection) {
    Invoke-WebRequest "https://www.wikidata.org/wiki/Special:EntityData/$($item.qid).json" -UserAgent 'ZeroConvert-IME dictionary (github.com/g0083/ZeroConvert-IME)' -OutFile "$source/wikidata/$($item.qid).json" -ErrorAction Stop
}
```

取得済みのファイルを固定してから、ローカルで辞書を生成する。

```powershell
$source = 'artifacts/downloads/additional-dictionary'
$lock = "$source/sources-20260723.json"
python scripts/run.py windows/scripts/additional_dictionary.py freeze --sudachi-dir "$source/20260723" --sudachi-version 20260723 --wikidata-dir "$source/wikidata" --selection windows/data/additional-dictionary-wikidata.json --output $lock

. ./windows/scripts/modern-llama.ps1
$null = Initialize-ModernLlama
$swiftArgs = @('--package-path','windows/server-swift','--scratch-path','windows/server-swift/.build-modern','-c','release','-Xcc','-D_CRT_USE_C_COMPLEX_H','-Xcxx','-D_CRT_USE_C_COMPLEX_H')
swift build @swiftArgs --product additional-dictionary-builder --build-system native
$swiftBin = swift build @swiftArgs --show-bin-path
$builder = Join-Path $swiftBin 'additional-dictionary-builder.exe'
$output = 'artifacts/build/additional-dictionary/trial-20260723'
python scripts/run.py windows/scripts/additional_dictionary.py generate --source-lock $lock --builder $builder --output $output
```

生成先は新規ディレクトリを指定する。`additional-dictionary.json` に入力のハッシュ、標準辞書リビジョン、採用・除外件数、辞書ファイルのハッシュを保存する。`entries.json` には追加語ごとのスコアと出典が入る。取得時の入力を保管しておけば、過去の版の辞書も生成できる。

## Windows試験IMEで比較する

azooKeyの元辞書は `Build-Trial.bat -DictionaryOnly -BaseDictionary`、比較用の辞書は `Build-Trial.bat -DictionaryOnly -AdditionalDictionaryDirectory <生成先>` で適用する。`Build-Trial.bat -DictionaryOnly` で現在の同梱辞書に戻る。切り替えるたびに入力アプリを開き直す。

- [90件の比較ケース](../../benchmarks/windows-rpc/dictionary-cases.tsv) は各入力の第1候補、上位5候補、1打鍵ごとのRPC応答時間を記録する。両版で同じモデル・設定を使い、同じケースを入力する。
- 実IMEの確認では、追加語を含む文を入力し、削除・カーソル移動・確定・取消・再変換を操作する。対照文の誤自動確定は、アプリ上の確定済みテキストで判定する。
- 採用条件は、新語30件の第1候補正答が5件以上増え、両ソースで改善があること。対照60件の第1候補の新規誤りと誤自動確定は0件とし、RPCの打鍵応答p95の増加は標準版の10%または2 msの大きい方までとする。比較結果は `artifacts/results/` に記録する。

## 初回試験の結果

SudachiDict Fullから5,000語、Wikidataから39語を追加した。同じ入力で2回生成し、辞書一式のハッシュを記録したマニフェストと追加語一覧が一致した。

Windows検証ビルド `0.8.1-dev.1795096002.gb53c6c8b` でZeroCon Model Nano v4.1 Q8を使い、学習を無効にした同一の隔離設定で比較した。

| 90件のRPC比較 | 標準辞書 | 追加辞書 |
| --- | ---: | ---: |
| 新語30件の第1候補正答 | 14 | 14 |
| Wikidata 15件の上位5候補内正答 | 2 | 15 |
| 対照60件の第1候補正答 | 47 | 47 |
| 1打鍵ごとのRPC応答時間 p95 | 46.5 ms | 48.7 ms |

追加辞書はWikidata語の候補漏れを減らした。「佐々木柚奈さんに会います」は標準辞書で上位5候補に入らず、追加辞書では第2候補に出た。対照文に第1候補の新規誤りはなかった。

同じQ8モデルを `llama-tokenize` で調べると、「佐々木柚奈」は `佐 / 々 / 木 / 柚 / 奈` の5トークンになる。今回の比較からは、この読みと表記の組をモデルが学習データで見たかどうかは分からない。

Windowsの候補統合では、Zenzaiで得た候補を先に置き、辞書のみの変換で得た候補を後ろに足す。今回の試験では、追加した語がどの段階で第1候補に届かなかったかまでは計測していない。上位候補への収録は辞書の効果を示し、第1候補の正答はライブ変換・自動確定に向けた順位付けの効果を示す。初回の第1候補の採用基準に達しなかったため、試験IMEは標準辞書に戻した。比較の詳細は `artifacts/results/additional-dictionary/controlled-comparison.json` に保存した。 [候補統合の実装](../../windows/server-swift/Sources/azookey-server/azookey_server.swift)

Win32入力欄では「鳴門山」の候補表示と、削除・カーソル移動・取消を確認した。「マルチ決済」はEnterで確定できた。確定語を選択したSpace再変換では候補が開かなかった。サーバーの再変換RPCは両辞書で「鳴門山」を「ナルトヤマ」として受理した。対照60件の自動確定は実IMEでの全件判定を行っていない。

## 5万語の選出と追加試験

5,039語版を基準に55,039語の試験用追加辞書を生成する。SudachiDict 20260723の読み・表記を、日本語Wikipediaの2026年11月の全記事見出しと本文サンプル、2023年11月の固定版本文で照合した。重複記事をまとめて103,080記事を数えた。採用された語の表記、読み、出現文書数、出典は `artifacts/build/additional-dictionary/selection-50000.json` に記録した。 [2026年11月の本文サンプル](https://dumps.wikimedia.org/other/cirrus_search_index/20261115/index_name%3Djawiki_content/) · [2023年11月の固定版本文](https://huggingface.co/datasets/wikimedia/wikipedia/tree/37efe7ff5541f3a0a021cb59ca3da0d184e008dd/20231101.ja)

- 同じ記事での出現は1回と数え、記事の見出しになっている語を加点する。本文2記事以上で出るか、記事見出しと一致する語を選考対象とした。既存語と同じ読みが多い場合は順位を下げ、追加は一つの読みにつき3件まで、既存10件以上の読みでは1件までとした。
- 一般語・専門用語20,000件、人名以外の固有名詞20,000件、人名10,000件を選出した。登録済み表記の別読みは追加を保留し、新しい表記も初回は1つの読みに絞った。
- 選出処理の `select` は固定済みCSV、5,039語版の辞書、本文・見出しファイルのURLとSHA-256を記載したコーパスマニフェストを受け取る。`extend` は選出結果を検証し、順引きと逆引きの両方を生成する。本文選出では作業領域のPython環境に `pyahocorasick==2.3.1` と `pyarrow==26.0.1` を使った。 [選出と生成のコマンド](../../windows/scripts/select_additional_dictionary.py)
- `cases` では、各区分の順位を三分して20件ずつ、合計180件を固定サンプルにする。前回の90件も含め、5,039語版と55,039語版を同じモデル・設定で計270件比較する。新規180件では文頭の対象語が上位5候補に入るかを主に見る。

### 5万語版の比較結果

2026年11月20日、ZeroCon Model Nano v4.1 Q8を使い、学習を無効にした同じ設定で比較した。変換サーバーとSwift DLLのハッシュは両方の測定で一致した。

| 指標 | 5,039語版 | 55,039語版 |
| --- | ---: | ---: |
| 新規180件の上位5候補内正答 | 118 | 156 |
| 新規180件の第1候補正答 | 102 | 104 |
| 対照60件の第1候補正答 | 47 | 47 |
| 1打鍵ごとのRPC応答時間 p95 | 54.1 ms | 54.3 ms |

対照文に第1候補の新規誤りはなかった。Win32入力欄では「でじたるついん」を打鍵し、「デジタルツイン」が第1候補に出ることを確認した。試験IMEは55,039語版を適用した状態で通常設定に戻し、辞書ファイルを照合済み。比較の詳細は `artifacts/results/additional-dictionary-50000/comparison.json` に、起動とメモリの参考測定は同ディレクトリの `*-runtime.json` に保存した。

辞書ファイルの合計は44.7 MBから49.7 MBに増えた。測定終了時のプライベートメモリは、変換サーバーと打鍵補正ワーカーを合わせて約36.5 MB増えた。各版1回の測定なので、次の増量では起動とメモリも同じ条件で複数回比較する。

### 2回目の5万組の選出と検証

55,039組の追加辞書（全579,045組）を基準に、同じSudachiDictとWikipediaの固定入力から50,000組を選んだ。内訳は一般語・専門用語20,000組、人名以外の固有名詞20,000組、人名10,000組。自動選出2回分を合わせて同じ読みへの追加を3組以下に抑えた。基準辞書に同音候補が10組以上ある読みには、前回までの自動追加が0組の場合に限って1組追加した。選出結果を `artifacts/build/additional-dictionary/selection-second-50000.json` に保存した。

既存55,039組の追加記録を維持し、全629,045組を含む辞書を `artifacts/build/additional-dictionary/trial-20260723-plus100000` に生成した。今回追加した全50,000組を順引きと逆引きで照合した。辞書ファイルの合計は49.7 MBから55.1 MBに増えた。

前回と同じ90件と新規180件に、今回増やした同音候補と競合する既存語30件を加え、各版300件を比較する。同音語では、基準版で第1候補に出た対象語が増量版で上位5候補から消えたケースを数える。起動測定は同じPCで各版3回プロセスを再起動し、起動から再変換応答までの時間と、応答直後のプライベートメモリを記録する。

2026年11月20日に、ZeroCon Model Nano v4.1 Q8を使い、学習を無効にした同じ設定と同じ変換バイナリで比較した。

| 指標 | 579,045組 | 629,045組 |
| --- | ---: | ---: |
| 新規180件の上位5候補内正答 | 111 | 151 |
| 新規180件の第1候補正答 | 81 | 81 |
| 対照60件の第1候補正答 | 47 | 47 |
| 同音語30件の上位5候補内正答 | 21 | 21 |
| 1打鍵ごとのRPC応答時間 p95 | 56.9 ms | 53.8 ms |
| 起動から再変換応答までの中央値 | 476.0 ms | 513.5 ms |
| 起動直後のプライベートメモリ中央値（サーバーと打鍵補正ワーカーの合計） | 357.2 MB | 393.7 MB |

300件すべてで第1候補の表記が一致した。Win32入力欄で「しぶやすかいについてしらべます」を入力し、「SHIBUYA SKYについて調べます」が第3候補に出た。途中削除と再入力、左右のカーソル操作後にEscで入力を取り消せることも確認した。実IMEでの確認時は自動確定を無効にした。比較結果と起動測定を `artifacts/results/additional-dictionary-100000/comparison-run` に保存した。試験IMEは629,045組の辞書を適用し、辞書ファイルを照合して通常設定で再起動した。

### 配布する辞書アセットを更新する

通常ビルドでは、[prepare_dictionary.py](../../scripts/prepare_dictionary.py) が固定したazooKeyの元辞書と `data/dictionary/additional-louds.zip` を結合する。試験済みの順引きと逆引きのハッシュを照合するため、通常ビルドで選出をやり直す必要はない。ただし結合結果は**配布物に同梱しない**。初回起動時に設定画面が取得する zip アセットとして配る（[日本語辞書の実行時取得](../dictionary-assets.md)）。

次回の選出・比較が済んだら `python scripts/prepare_dictionary.py --freeze-trial <検証済みの生成先>` で差分とライセンス文書を更新し、続けて [build-dictionary-asset.py](../../scripts/build-dictionary-asset.py) で zip と `windows/deployment/dictionary-catalog.json` を作り直す。zip は GitHub リリースへ置き、カタログ・`windows/crates/shared/src/runtime_assets.rs`・`DictionaryDownloadManager.kt` の固定値を同じ版にそろえる。WindowsパッケージとAndroid APKの両方を作り、どちらにも辞書が入っていないことを検査する。

### 配布時に同梱する表記

- SudachiDict Fullの対象版、著作権表示、Apache License 2.0全文、上流の `LEGAL` を辞書と一緒に同梱する。追加語を抽出し、読みを正規化してazooKey形式に組み込んだことを記す。上流の `LEGAL` にはUniDicとNEologd等の表記が含まれる。 [SudachiDict v20260723のLEGAL](https://github.com/WorksApplications/SudachiDict/blob/v20260723/LEGAL) · [Apache License 2.0 §4](https://www.apache.org/licenses/LICENSE-2.0#redistribution)
- Wikidataの構造化データはCC0 1.0として記載し、取得元とQIDを選出記録に残す。 [Wikidataのライセンス](https://www.wikidata.org/wiki/Wikidata:Licensing)
- 日本語Wikipediaは出現頻度を集計したコーパスとして、取得元とスナップショットの日付を記す。生成した辞書には、記事本文や抜粋を収録していない。 [Wikimediaの利用規約](https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use/en)
- 辞書ZIPの `Dictionary/AdditionalDictionaryNotices/` にSudachiDictの `LEGAL` とライセンス全文、Wikidataの出典を収録する。WindowsとAndroidのライセンス画面も、アプリに収録した同じ文書を表示する。 [Windows配布用ライセンス一覧](../../windows/deployment/THIRD_PARTY_LICENSES.md) · [Androidライセンス一覧](../../android/THIRD_PARTY_LICENSES.md)
