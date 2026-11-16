# ZeroConvert IME の紹介サイト

公開先は [GitHub Pages](https://g0083.github.io/ZeroConvert-IME/)。`web-pages-smoke` への push で [Pages ワークフロー](../.github/workflows/pages-smoke.yml) が Wasm、辞書、モデルを組み立てる。紹介ページの本文とデモの画面は `pages/index.html` にある。

アプリのダウンロード欄は、閲覧時に GitHub の最新リリースから Windows インストーラーと Android のユニバーサル APK を探す。取得に失敗した場合や該当ファイルがない場合は、最新リリースページを開く。

デモは変換エンジン、辞書、GGUF の受信量を別々に表示する。HTTP の圧縮やヘッダーの欠落で全体のバイト数が分からないときは、受信した量を表示する。モデルの初期化と試し変換が完了してから入力欄を有効にする。

候補一覧と原文フローティングは、Windows の WebView をブラウザの iframe に組み込んで表示する。`pack-candidate-window.py` と `pack-reading-window.py` が配信用 HTML を生成する。原文の上に補正差分や先読みの続きが出たら、キー操作か表示のクリックで採用・取消ができる。

デモの「表示方法」で Windows と Android を切り替えられる。スマートフォンとタブレットでは初期表示が Android になる。Android の12キー配列、かな修飾、配色、記号・絵文字は `pack-android-keyboard.py` が Android 版のソースと資産から生成する。キーをタップするか、押した位置からフリックすると入力できる。 [Unicode 絵文字データのライセンス](unicode-emoji-license.txt)

ローカルでの確認には、ワークフローと同じ構成に組み立てたサイトのディレクトリを指定する。

```powershell
node web/check-load-progress.mjs
node web/check-model-wasm.mjs dist/pages-smoke
node web/check-model-assist.mjs dist/pages-smoke
node web/check-model-learning.mjs dist/pages-smoke
node web/check-browser-parity.mjs dist/pages-smoke msedge chrome
```

最後のテストには Playwright と Edge、Chrome が必要。Playwright を別の場所に置いている場合は `PLAYWRIGHT_MODULE` でモジュールを指定できる。
