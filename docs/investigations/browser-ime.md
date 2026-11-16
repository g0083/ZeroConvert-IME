# ZeroConvert IME のブラウザ実行

ページを開くと、ブラウザがエンジン・辞書・モデルを取得し、Web Worker 内で打鍵を処理する構成を検討した。通常変換には Windows 版と共通の Swift 変換コードと llama.cpp を使い、自動確定・タイポ補正・先読みには共有 Rust コードを使う。ブラウザ用アダプターが打鍵イベント、未確定範囲、候補、確定を扱う。

## 確認結果

- `engine/rust` は既存ソースのまま `wasm32-wasip1` 向けにビルドできた。Node の WASI 環境で、同じ読みを使う補正・先読みの判定と、Swift 側が読みを補正したときに追加補正を抑える判定を検証した。
- Ubuntu 22.04 の WSL と Swift 6.4.2 Wasm SDK で共有 Swift コンバーターを試験ビルドした。辞書ビルダーが使う `Data.WritingOptions.atomic` は WASI で利用できない。試験コピーでこれを外すと、Zenzai を外した辞書単体構成でも llama.cpp の代替型に現行の変換コードが参照するフィールドがなく、コンパイルが止まった。
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

[`web-pages-smoke` ブランチ](https://github.com/g0083/ZeroConvert-IME/tree/web-pages-smoke) の push で [検証ワークフロー](../../.github/workflows/pages-smoke.yml) が動く。共有 Rust Wasm をビルドし、Node で判定確認を通したあと、[`web/pages/`](../../web/pages/) と組み合わせて GitHub Pages に配信する。Pages の公開元は GitHub Actions。Wasm は Actions の成果物で配信する。

[試験ページ](https://g0083.github.io/ZeroConvert-IME/) は配信された Wasm の `application/wasm` ヘッダーを確認してから、実際に `WebAssembly.compileStreaming` と共有エンジンの判定を呼ぶ。画面には二件の判定結果が表示される。辞書変換とモデル推論の検証には、それぞれの Wasm ビルドが必要になる。
