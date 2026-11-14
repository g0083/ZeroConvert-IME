# キーボード補正の通常変換評価

ルートから実行する。Swift Windows SDKと通常のWindowsビルド依存が必要。
出力先は新しいフォルダを指定する。既存の `cases.json` は保持する。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File benchmarks/keyboard-correction/run.ps1 `
  -OutputDirectory D:/angra/ZeroConvert-IME/artifacts/results/keyboard-correction-new `
  -ModelPath D:/angra/ZeroConvert-IME/artifacts/models/zerocon/zerocon-nano-v4-q8_0.gguf
python scripts/run.py benchmarks/keyboard-correction/summarize.py artifacts/results/keyboard-correction-new/cases.json
```

`-ModelPath` を省くと辞書単体を評価する。`-TestFilter '.*'` は既存Swiftテストも実行する。
Windows上のSwift Testingで共有状態が干渉しないよう、テストを直列に実行する。
モデルを指定した場合は通常N-bestとZenzaiを本体と同じ関数で統合する。

評価対象は登録タイポ92件、その正常な読み、ローマ字表記の互換入力45件、
正常入力19件、文脈付きの既存300件データとなる。`group` で結果の由来を識別する。
正常な読みを打鍵へ戻す際は現在のパーサーで確定できる `nn` / `xtu` を使用する。
末尾未確定 `n` やハイフンを含む互換入力は実際の読みも記録する。

正常入力では、キー境界付近を想定した重み0.9の隣接キー証拠も渡す。
先頭候補の補正読みが新しく発生した場合はテストを失敗させる。
登録タイポの一致率と、未登録255件の一致率をそれぞれ集計する。
タッチによる正例は `watadi` の実辞書照合と、長い補正読みのFFI回帰テストで検査する。

遅延の比較時は他のビルド・モデル処理を止め、同じモデル・設定・端末を使う。
この評価は共有Swiftの通常変換を対象とし、Rustの非同期AIアシストは呼び出さない。
TSFやInputConnectionの採用・取消・実機性能は、各OSの入力アプリで確認する。
