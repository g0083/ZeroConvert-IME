# ブラウザ版サイトの公開手順（Cloudflare Pages）

ブラウザ版（`web/`）は、Cloudflare Pages プロジェクト `zeroconvert-ime` に**手元から Wrangler で直接デプロイ**します。main への push では Cloudflare も GitHub Actions もビルドしません。

## 仕組み

- Cloudflare Pages の Git 連携は残しますが、自動デプロイは無効化しています（本番・プレビューとも）。
- 公開する中身は、GitHub Release にピン留めした成果物 `web/site-release.json` の `zerocon-site.tar.gz` です。`node web/fetch-site-release.mjs` が取得・検証して `dist/pages` に展開します。
- `.github/workflows/deploy-cloudflare-pages.yml` は手動実行専用の検証ワークフローです。`checks.yml` は pull request のみで動きます。

## 通常の公開（成果物を変えない場合）

1. 公開する内容が `web/site-release.json` のピン留め版でよいことを確認する。
2. 取得と展開を行う。
   ```bash
   cd /d/angra/ZeroConvert-IME
   node web/fetch-site-release.mjs
   ```
3. デプロイする（初回のみ `npx wrangler login` が必要。資格情報はご自身の端末で扱う）。
   ```bash
   npx wrangler@latest pages deploy dist/pages \
     --project-name=zeroconvert-ime \
     --branch=main \
     --commit-hash="$(git rev-parse HEAD)" \
     --commit-message="$(git log -1 --pretty=%s)"
   ```
   `--branch=main` を指定すると本番（Production）として公開されます。

## 新しい成果物を作る場合

1. WSL（Ubuntu）で全体をビルドし、`dist/pages` を作る。
   ```bash
   bash web/build-site.sh
   ```
2. 新しい Release を作り、`web/site-release.json` を更新する。
   ```bash
   bash web/publish-site-release.sh
   ```
3. 更新された `web/site-release.json` をコミットして push する。
4. 上記「通常の公開」の 2〜3 を実行する。

## 確認

- 本番: `https://zeroconvert-ime.pages.dev/asset-manifest.json` の `build.commit` を確認する。
- ダッシュボード: Workers & Pages → `zeroconvert-ime` → デプロイ で、最新の Production が成功していることを確認する。
- 任意の検証: `gh workflow run deploy-cloudflare-pages.yml` で成果物の検証（Wasm 推論を含む）を手動実行する。

## 注意

- Cloudflare の自動デプロイを再度有効にすると、push のたびにビルドが走ります。
- Direct Upload への切替はできません（Git 連携プロジェクトのため）。
