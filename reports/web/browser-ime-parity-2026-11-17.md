# Web 版 ZeroConvert IME の比較記録（2026-11-17）

Windows Swift DLL と Web Wasm に、通常入力、未確定ローマ字の削除、文中の編集、文節の伸縮、部分確定後の入力を通した。11例の129操作中、128操作が一致した。差が残ったのは `okur` の4打鍵目で、「オクR」と「置くr」が第3・第4候補で入れ替わった。両側に120候補があり、先頭の「おくr」と候補の集合は一致した。

| 照合条件 | 値 |
| --- | --- |
| Windows 比較対象 | `dist/packages/20261115-120436-354/payload` の Swift DLL。SHA-256 は `59967055b7862403abca9f21050387b5edf0b21efa3b8eb47a240e58fa53c106` |
| Web 比較対象 | ローカルの Swift 6.4.2 Wasm。生成物は `artifacts/pages-smoke/parity-site`。Swift Wasm の SHA-256 は `34bd53a1c32b5399c2f09eb4ab0151655046a4458e07d2e53d8d08633c2cfd11` |
| モデルと設定 | Nano v4.1 IQ4_emb8、CPU、GPU レイヤー0、学習なし。GGUF の SHA-256 は `6a99ca4e1c46b3f6fb86dcf884ea0652c967d86d606c12268b33d894d3853b24` |
| 辞書 | リポジトリの辞書固定先は `b05798b43679c385ce2179fb746512d7643dfe12`。照合スクリプトは Web に梱包した各辞書ファイルの内容を Windows パッケージと比較する |

比較は一打鍵ずつ行い、読み、原打鍵、カーソル、候補の本文・後続文字・読み・消費長を記録した。Windows 側は既存パッケージ、Web 側は作業ツリーの Swift ソースからビルドしている。候補順の差が生じる箇所は特定できたが、発生原因の判定には両側のソースをそろえた比較が残る。

変換器の呼び出しから候補を読み出すまでの時間は、Windows DLL が中央値8.9 ms、95パーセンタイル19.4 ms、Web Wasm が中央値76.6 ms、95パーセンタイル151.5 msだった。129操作を同じPCで記録した1回分で、ブラウザの起動や描画は別計測になる。

```powershell
python web/record-windows-parity.py dist/packages/20261115-120436-354/payload artifacts/pages-smoke/parity-site artifacts/results/browser-ime-windows-parity.json
node web/check-windows-parity.mjs artifacts/pages-smoke/parity-site artifacts/results/browser-ime-windows-parity.json
```

## 補正と先読み

補正と先読みは、通常変換とは別の Worker で実行する。Rust は Windows と共通の要求生成と応答検証を使う。キーボード補正は同じ逆引き辞書と既存のスコアリング処理を使い、通常変換の候補順を保ったままアシスト候補を追加する。自動補正は入力欄が最新の応答だと確認してから反映する。

実モデルで `okseri` が「おかえり」になり、正常な `okaeri` には補正候補が出なかった。「前文。」に続く「よろしくお願いし」には「ます。」を提案した。これらは動作を確かめる例で、誤補正率や先読みの精度を示すには、入力例と Windows CPU 側の採取結果を増やす必要がある。

Windows の CPU サーバーと Web の推論器に同じ先読み5例を通した。Windows は12〜23 msで判定し、2例で候補を返した。Web は同じ時間制限で5例とも候補を返さず、いずれも最初の推論の上限に近い約250 msを要した。制限を3倍にした試行では、「昨日の夜は」の続きは一致したが、「プログラムの」の続きが異なり、「明日の会議で」には Web だけが候補を出した。時間制限の延長だけでは出力の差が残るため、試験ページには元の制限を使っている。 [比較スクリプト](../../web/compare-prediction.mjs)

Edge と Chrome では、未確定の `k` の削除、文節の伸縮、キャレットに追従する候補窓、読みルビ、クリックでの確定、フォーカス移動時の開閉を確認した。`okseri` は自動で「おかえり」になり、Backspace で元の「おkせり」に戻った。候補窓が補正差分を隠さないことも検査した。試験画像は `artifacts/results/browser-ime-parity/` に保存した。 [ブラウザ確認手順](../../web/check-browser-parity.mjs)

## 学習と Windows 実機

`hashi` の候補「橋」を学習すると、再読み込み後も「橋」が先頭になった。学習を無効にすると保存済みの順位を使わず、個別の忘却で元の順位に戻り、全消去で永続化対象のファイルがなくなった。IndexedDB では、別タブが更新した版を古い読み込み状態から上書きできないことを確認した。読み取り専用モードでは順位を読み込めるが、Swift が候補 ID を発行しないため、個別の忘却には学習が有効な状態が必要になる。

Windows の Win32 試験欄では、プロセス内で ZeroConvert の TSF プロファイルを選び、「明日のk」の未確定表示、Backspace、左右移動と Delete、候補窓、`okseri` に対する「おかえり」を観察した。この PC の実機設定は Vulkan・学習有効・先読み無効だった。CPU・学習なしの比較は上の DLL 記録で行っている。 [Win32 試験欄](../../benchmarks/windows-rpc/ime-verification.cpp) · [TSF プロファイルを選ぶ API](https://learn.microsoft.com/en-us/windows/win32/api/msctf/nf-msctf-itfinputprocessorprofilemgr-activateprofile)

Web 用の Swift・Rust・C++ の Wasm をビルドした。共有 Rust の回帰110件と、専用の Windows CPU サーバーを使う先読み試験1件を通した。Android は `Build-Android.bat` で JVM テスト、lint、arm64-v8a と x86_64 の debug APK 作成を通した。検証対象はローカルの成果物で、GitHub Pages への配信、Windows 試験 IME や Android 端末への適用、インストーラー作成、Git 保存は行っていない。
