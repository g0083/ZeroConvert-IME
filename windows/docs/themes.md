# Windows版のテーマカラー

設定メニューの「外観・テーマ」（`/appearance`）で、設定画面とIMEのテーマを個別に選択する。
ダークブルー、グラファイト、フォレスト、プラム、サクラ、ペーパーの6種類。前4種類は暗色、後2種類は明色で、OSのライト／ダーク設定とは独立する。

設定は既存の `%APPDATA%\Azookey\settings.json` に自動保存される。

```json
"appearance": {
  "frontend_theme": "dark_blue",
  "ime_theme": "dark_blue"
}
```

既存設定に `appearance` がない場合、および各項目がない場合はダークブルーを補う。設定画面は保存成功時に反映し、失敗時は直前の配色を維持する。テーマだけの変更では変換サーバーへの設定適用を必要としない。

IMEの対象は候補一覧、読み・アシスト表示、入力モード表示の3つ。UIプロセス内の専用スレッドが1秒ごとに設定ファイルの更新時刻とサイズを確認し、変更時だけ設定を読み直す。配色が変わった場合のみUIイベントを送る。入力・TSF・候補更新の経路では設定ファイルを読まない。変更の検知に通常最大約1秒かかる。

配色の共通定義は `crates/shared/themes.json`。Rustの設定型は `crates/shared/src/lib.rs`、設定画面への適用は `frontend/src/lib/themes.ts` と `components/theme-provider.tsx`、IMEへの適用は `crates/ui/src/theme.rs` と `theme.js`。Swift変換エンジンへ渡すパラメータの追加はない。

検証コマンド（`windows/` 基準）:

```powershell
cargo test -p shared --lib
cargo test -p frontend appearance_saves --lib
cargo check -p ui
# frontend/ 内
npm run typecheck
npm test
npx vite build
# 既存のPlaywright環境を利用
$env:PLAYWRIGHT_MODULE='<Playwrightのindex.mjsの絶対URL>'
$env:PLAYWRIGHT_CHANNEL='msedge'
node --test tests/themes.browser.mjs
```

ブラウザテストはTauriをモックし、個別選択、再読込、保存失敗、狭い画面、および全IME表示の6配色をOSの明暗両方で確認する。実際のTSF入力とWebView2を含むネイティブ確認とは区別する。
