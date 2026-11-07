# Windows: 入力先の InputScope に合わせた入力モード自動切替（実装計画）

状態: 実装計画（未実装）。対象: `windows/` のみ。Androidは別計画
[android-input-type-mode.md](android-input-type-mode.md) を使う。

## 目的

フォーカスした入力欄が InputScope で「英数字・数値・URL・メール等」または
「ひらがな・カタカナ・漢字」を宣言している場合、入力開始時にその意図へ
入力モード（`InputMode::Kana` / `InputMode::Latin`）を合わせる。

- 合わせるのは**入力開始時のみ**。ユーザーが言語バー・Ctrl+Space・英数キーで
  モードを変えたら、その選択は次のフォーカス移動まで保持する（再適用しない）。
- InputScope が未設定・曖昧・判定不能なら**現在のモードを維持**する。
- 設定で無効化できる。既定は無効（既存ユーザーの挙動を変えない）。

## 前提: 取得できる情報

`GUID_PROP_INPUTSCOPE` / `ITfInputScope::GetInputScopes` で `InputScope` の配列が取れる。
本リポジトリには既に取得実装がある。**新規にCOM plumbingを書かない**こと。

- `windows/crates/client/src/engine/composition.rs:4786` `is_sensitive_input_scope`
- 同 `:4798` `input_scope_list_contains_sensitive`
- 同 `:4819` `context_has_sensitive_input_scope`（`read_edit_session` + `GetSelection` + `GetAppProperty`）
- 同 `:4849` `range_has_sensitive_input_scope`（`pub(crate)`、範囲指定版）
- `windows/crates/client/src/engine/composition/jev.rs:12` `input_scope_allows_remote_scoring`
  （`VARIANT` から `ITfInputScope` を取り出す例）

`InputScope` 定数は `windows` crate の `Win32::UI::TextServices` に揃っている
（`IS_URL`, `IS_EMAIL_SMTPEMAILADDRESS`, `IS_NUMBER`, `IS_DIGITS`, `IS_HIRAGANA`,
`IS_KATAKANA_FULLWIDTH`, `IS_KANJI`, `IS_ALPHANUMERIC_HALFWIDTH` 等）。

## 設計

### 1. 判定は純粋関数に閉じる（新規モジュール）

`windows/crates/client/src/engine/input_scope_mode.rs` を新設する。

```rust
pub(crate) fn mode_for_scopes(scopes: &[InputScope]) -> Option<InputMode>
```

- `InputMode` は `crate::engine::input_mode::InputMode`（`Latin` / `Kana` の2値）。
- 戻り値 `None` は「意見なし = 現在のモードを維持」。
- 判定順: **Kana 指定が1つでもあれば Kana**、無ければ Latin 指定が1つでもあれば Latin、
  どちらも無ければ `None`。`IS_DEFAULT` は `None` 扱い。
- 判定表（この表以外を勝手に増やさない）:

| モード | InputScope |
| --- | --- |
| `Kana` | `IS_HIRAGANA`, `IS_KATAKANA_FULLWIDTH`, `IS_KATAKANA_HALFWIDTH`, `IS_KANJI` |
| `Latin` | `IS_ALPHANUMERIC_HALFWIDTH`, `IS_ALPHANUMERIC_FULLWIDTH`, `IS_URL`, `IS_EMAIL_SMTPEMAILADDRESS`, `IS_EMAIL_USERNAME`, `IS_EMAILNAME_OR_ADDRESS`, `IS_LOGINNAME`, `IS_FILE_FILENAME`, `IS_FILE_FULLFILEPATH`, `IS_NUMBER`, `IS_NUMBER_FULLWIDTH`, `IS_DIGITS`, `IS_TELEPHONE_FULLTELEPHONENUMBER`, `IS_TELEPHONE_COUNTRYCODE`, `IS_TELEPHONE_AREACODE`, `IS_TELEPHONE_LOCALNUMBER`, `IS_CURRENCY_AMOUNT`, `IS_CURRENCY_AMOUNTANDSYMBOL`, `IS_DATE_FULLDATE`, `IS_DATE_MONTH`, `IS_DATE_DAY`, `IS_DATE_YEAR`, `IS_DATE_MONTHNAME`, `IS_DATE_DAYNAME`, `IS_TIME_FULLTIME`, `IS_TIME_HOUR`, `IS_TIME_MINORSEC`, `IS_ADDRESS_FULLPOSTALADDRESS`, `IS_ADDRESS_POSTALCODE`, `IS_ADDRESS_STREET`, `IS_ADDRESS_STATEORPROVINCE`, `IS_ADDRESS_CITY`, `IS_ADDRESS_COUNTRYNAME`, `IS_ADDRESS_COUNTRYSHORTNAME`, `IS_PERSONALNAME_FULLNAME`, `IS_PERSONALNAME_PREFIX`, `IS_PERSONALNAME_GIVENNAME`, `IS_PERSONALNAME_MIDDLENAME`, `IS_PERSONALNAME_SURNAME`, `IS_PERSONALNAME_SUFFIX`, `IS_ONECHAR`, `IS_MAPS`, `IS_NAME_OR_PHONENUMBER`, `IS_FORMULA`, `IS_FORMULA_NUMBER` |
| `None`（維持） | `IS_DEFAULT`, `IS_TEXT`, `IS_CHAT`, `IS_CHAT_WITHOUT_EMOJI`, `IS_SEARCH`, `IS_SEARCH_INCREMENTAL`, `IS_PRIVATE`, `IS_PASSWORD`, `IS_NUMERIC_PASSWORD`, `IS_NUMERIC_PIN`, `IS_ALPHANUMERIC_PIN`, `IS_ALPHANUMERIC_PIN_SET`, 上表以外すべて |

`IS_SEARCH` を `None` にするのは意図的。検索欄は日本語入力のまま使う利用者が多く、
`IS_URL` と併記されることが多いため、`IS_URL` 側で Latin に倒れる。

### 2. 設定項目

`windows/crates/shared/src/lib.rs` の `GeneralConfig`（`:676`）に追加する。

```rust
/// Follow the focused field's InputScope when input starts.
#[serde(default)]
pub follow_input_scope_mode: bool,
```

- `Default for GeneralConfig`（`:726`）に `follow_input_scope_mode: false` を追加。
- `CONFIG_VERSION`（`:233`）は上げない（`serde(default)` で後方互換）。
- 設定画面: `windows/frontend/src/pages/general.tsx`
  - `GeneralConfigState`（`:33`）に `follow_input_scope_mode: boolean;` を追加。
  - `DEFAULT_GENERAL_CONFIG`（`:61`）に `follow_input_scope_mode: false` を追加。
  - `normalizeGeneralConfig`（`:150`）は既存の型一致ループで自動的に処理されるため変更不要。
  - 「英数・かな切り替え」セクション（`:515`）に `SettingsRow` + `Switch` を1行追加。
    `updateGeneralConfig("follow_input_scope_mode", value)` を使う（既存関数を再利用）。
    説明文の例: 「入力欄が英数字・数値・URL等を宣言している場合は英数、かな・漢字を
    宣言している場合は日本語で入力を始めます。入力中に切り替えたモードはそのまま使えます。」
- 配布初期設定: `windows/deployment/settings.json` の `general` に
  `"follow_input_scope_mode": false` を追加する。
  `windows/settings.json` は開発用のローカル設定なので触らない。

### 3. 適用タイミングと経路

**適用は入力開始時のみ。キー入力ごとに再評価しない。**

フック先: `windows/crates/client/src/tsf/thread_mgr_event_sink.rs:93` `OnSetFocus`。
既に `set_keyboard_disabled_for_document_mgr(focus)` を呼んでいる箇所の直後に追加する。

```
OnSetFocus
  └ set_keyboard_disabled_for_document_mgr(focus)   // 既存
  └ request_input_scope_mode_switch(focus)          // 追加
       ├ 設定 follow_input_scope_mode が false なら即 return
       ├ keyboard_disabled なら return（パスワード欄等）
       ├ 現在の composition が空でなければ return
       ├ request_async_read_edit_session で InputScope を読む
       │    callback: mode_for_scopes(...) の結果を Cell に保存
       │    completion: 保存された Option<InputMode> を取り出して適用
       └ 適用: 現在モードと異なる場合のみ
            request_input_mode_switch_after_composition(mode)
```

重要な制約:

- **edit session のコールバック内でモード切替を実行しない**。`request_async_read_edit_session`
  の `completion`（`Rc<dyn Fn()>`）で適用する。`selection_count.rs:142` の
  `request_selection_count_update` が同じ構造の参考実装。
- モード切替は既存の `request_input_mode_switch_after_composition`
  （`composition.rs:7187`）を使う。これが composition 終了・`update_lang_bar()`・
  候補ウィンドウのモードラベル更新まで面倒を見る。**`IMEState::set_input_mode` を
  直接呼ばない**（言語バーの「あ/A」表示がずれる）。
- 現在モードと同じなら何もしない（言語バーと候補ウィンドウの無駄な更新を避ける）。
- `OnSetFocus` は `focus: None`（フォーカス喪失）でも呼ばれる。`None` のときは何もしない。
- 世代管理: `selection_count` と同様に、読み取り開始時の世代を控え、completion 時に
  フォーカスが変わっていたら適用しない。`has_same_com_identity`（`tsf/edit_session.rs`）で
  現在の `layout_sink_context` と比較する。連続フォーカス移動で古い結果を適用しないこと。
- 待機・同期I/O・無制限処理を追加しない。読み取りは非同期 edit session 1回のみ。

`Activate`（`tsf/text_input_proccesor.rs:135`）への追加は**必須ではない**。
`OnSetFocus` が入力開始時に発火するため、まず `OnSetFocus` だけで完結させる。

### 4. ユーザー操作との関係

- 言語バー・Ctrl+Space・英数キーによる切替は `IMEState::set_input_mode` を通る。
  自動適用は `OnSetFocus` でしか走らないので、入力中に押した切替は上書きされない。
- 同じ欄に戻ってきた場合は再度 InputScope を読むため、自動適用が再び効く（意図どおり）。
- 翻訳モード中は `stop_translation_mode()` が `OnSetFocus` で走るため、追加処理は
  その後に置く。

## テスト

### 必須（自動）

`input_scope_mode.rs` の `#[cfg(test)] mod tests` に純粋関数のテストを置く。
COM は不要（`InputScope` は `InputScope(i32)` のタプルなので直接構築できる）。

- `IS_DEFAULT` のみ → `None`
- `IS_HIRAGANA` → `Kana`
- `IS_KATAKANA_FULLWIDTH` → `Kana`
- `IS_KANJI` → `Kana`
- `IS_URL` → `Latin`
- `IS_EMAIL_SMTPEMAILADDRESS` → `Latin`
- `IS_NUMBER` / `IS_DIGITS` → `Latin`
- `IS_ALPHANUMERIC_HALFWIDTH` → `Latin`
- `[IS_DEFAULT, IS_URL]` → `Latin`
- `[IS_URL, IS_HIRAGANA]` → `Kana`（Kana 優先）
- `[IS_SEARCH]` → `None`
- `[IS_PASSWORD]` → `None`
- 空スライス → `None`

`crates/shared/src/lib.rs` のテストに、`GeneralConfig::default().follow_input_scope_mode == false`
と、`follow_input_scope_mode` を含まない旧JSONが読めること（`serde(default)`）を追加する。

### 実行する確認

```
cd windows
cargo test -p azookey-client
cargo test -p shared
cargo build -p azookey-client
```

`cargo fmt` 相当（既存の rustfmt 設定）に合わせる。

### 手動確認（この計画では実施しない）

実IME確認は `Build-Trial.bat` の担当者が単独で行う。**このタスクでは
`Build-Trial.bat` / `Apply-Trial.bat` を実行しない**（試験IMEの適用担当を一つに保つ）。

手動確認の観点（後続作業者向け）:

1. 設定で `follow_input_scope_mode` を ON にする。
2. メモ帳（`IS_DEFAULT`）→ モードが変わらないこと。
3. Edge/Chrome のアドレスバー（`IS_URL`）→ 英数で始まること。
4. 日本語サイトの検索欄 → 日本語のままであること。
5. 数値入力欄（`IS_NUMBER`）→ 英数で始まること。
6. パスワード欄 → モードが変わらず、キーボード無効のままであること。
7. 自動適用後に Ctrl+Space でモードを変え、そのまま入力 → 上書きされないこと。
8. 同じ欄から別の欄へフォーカスを移す → 移動先の InputScope で再適用されること。
9. 言語バーのアイコン（あ/A）が実際のモードと一致すること。

## 触ってはいけないもの

- `android/` 配下（別エージェントが並行作業中）。
- `docs/investigations/README.md`（統合時に親が更新する）。
- 配布DLL/EXE、`ZeroConvertIME-windows/`、`dist/`、モデル重み。
- `windows/settings.json`（ローカル開発設定）。
- 既存の `is_sensitive_input_scope` の判定内容（パスワード判定を緩めない）。
- Git コミット・push。

## 完了条件

- 上記の自動テストが通り、`cargo build` が成功する。
- 設定画面に項目が表示され、`general.follow_input_scope_mode` として保存される。
- 変更ファイルと実行コマンド・結果を報告する。
- 実IME未適用・未確認であることを明記する。
