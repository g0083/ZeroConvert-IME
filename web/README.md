# ZeroConvert IME の紹介サイト

公開先は [GitHub Pages](https://g0083.github.io/ZeroConvert-IME/)。`web-pages-smoke` への push で [Pages ワークフロー](../.github/workflows/pages-smoke.yml) が Wasm、辞書、モデルを組み立てる。紹介ページの本文とデモの画面は `pages/index.html` にある。

デモは変換エンジン、辞書、GGUF の受信量を別々に表示する。HTTP の圧縮やヘッダーの欠落で全体のバイト数が分からないときは、受信した量を表示する。モデルの初期化と試し変換が完了してから入力欄を有効にする。

ローカルでの確認には、ワークフローと同じ構成に組み立てたサイトのディレクトリを指定する。

```powershell
node web/check-load-progress.mjs
node web/check-model-wasm.mjs dist/pages-smoke
node web/check-model-assist.mjs dist/pages-smoke
node web/check-model-learning.mjs dist/pages-smoke
node web/check-browser-parity.mjs dist/pages-smoke msedge chrome
```

最後のテストには Playwright と Edge、Chrome が必要。Playwright を別の場所に置いている場合は `PLAYWRIGHT_MODULE` でモジュールを指定できる。
