# ZeroConvert IME のブラウザ実行

ページを開くと、ブラウザがエンジン・辞書・モデルを取得し、Web Worker 内で打鍵を処理する構成を検討した。通常変換には Windows 版と共通の Swift 変換コードと llama.cpp を使い、自動確定・タイポ補正・先読みには共有 Rust コードを使う。ブラウザ用アダプターが打鍵イベント、未確定範囲、候補、確定を扱う。

## 確認結果

- `engine/rust` は既存ソースのまま `wasm32-wasip1` 向けにビルドできた。Node の WASI 環境で、同じ読みを使う補正・先読みの判定と、Swift 側が読みを補正したときに追加補正を抑える判定を検証した。
- Ubuntu 22.04 の WSL と Swift 6.4.2 Wasm SDK で、共有 Swift コンバーターの辞書変換を確認した。Web 向けのステージング時にモデル依存部分を分離し、WASI で使えない辞書ビルダーのアトミック書き込み指定を取り除く。ブラウザは共有辞書をメモリに展開し、学習を無効にした状態で変換を実行する。
- リポジトリ内の llama.cpp は単一スレッド WASI 向けの CMake 構成まで通った。元のソースでは `ggml-threading.cpp` の `std::mutex` でコンパイルが止まった。試験コピーでロックとモデルローダーの `std::async` を単一スレッド向けに変えると、次に AMX ソースの `std::thread` で止まった。ブラウザ用の C++ ビルドでは、CPU バックエンドのソース選択とスレッド依存の整理が必要になる。

Rust の判定はリポジトリルートから再実行できる。出力先は Git 管理外の `artifacts/web-engine/`。

```powershell
rustup target add wasm32-wasip1
cargo rustc --locked --manifest-path engine/rust/Cargo.toml --target wasm32-wasip1 --target-dir artifacts/web-engine --release --lib --crate-type cdylib -- -C link-arg=--export=malloc -C link-arg=--export=free
node web/check-assist-wasm.cjs artifacts/web-engine/wasm32-wasip1/release/zerocon_engine.wasm
```

## 静的ホスティングの条件

単一スレッドの Wasm と辞書・モデルを静的ファイルとして配信できれば GitHub Pages を利用できる。`SharedArrayBuffer` を使うマルチスレッド構成では、`COOP` と `COEP` を `_headers` で設定できる Cloudflare Pages が候補になる。[GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages) ・ [Cloudflare Pages のヘッダー設定](https://developers.cloudflare.com/pages/configuration/headers/)

Cloudflare Pages の上限は 1 ファイル 25 MiB。既定の Nano v4.1 IQ4_emb8 は 19,602,048 B、Q8 は 29,039,232 B。辞書全体は検証に使ったパッケージで 43,909,880 B だったため、Cloudflare Pages で一括取得する場合は複数アセットに分ける。公開にはライセンスと辞書・モデルの出典も含める。[Cloudflare Pages の制限](https://developers.cloudflare.com/pages/platform/limits/)

## GitHub Pages の試験ページ

[`web-pages-smoke` ブランチ](https://github.com/g0083/ZeroConvert-IME/tree/web-pages-smoke) の push で [検証ワークフロー](../../.github/workflows/pages-smoke.yml) が動く。共有 Rust Wasm、共有 Swift の辞書変換 Wasm、固定リビジョンの辞書を作り、Node で判定と変換を確認してから [`web/pages/`](../../web/pages/) と組み合わせて配信する。Pages の公開元は GitHub Actions。Wasm と辞書は Actions の成果物で配信する。

[試験ページ](https://g0083.github.io/ZeroConvert-IME/) は Wasm と辞書を読み込むと入力欄を有効にする。`ashitanokaigi` を入力すると読みと候補が表示される。Space または上下キーで候補を移動し、Enter か候補クリックで確定できる。Rust 側の補正・先読み依頼の判定は独立した動作確認欄に表示する。ZeroCon Model を使った推論は未接続。
