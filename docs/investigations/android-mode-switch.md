# Androidの英字から日本語へのモード切替（2.0.9-dev）

2026-10-26、未確定の英字を入力して日本語モードへ切り替え、そのまま続きを打つと
英字が置き換わる問題を修正した。切替時には補完候補を採用せず、入力した英字そのものを
確定してから次のモードへ進む。

## 原因と変更

KeyboardRootViewのモード切替はconfirmCurrentを呼んでいたが、サービスの
confirmAsyncは日本語のComposingTextだけを調べ、空なら処理を終えていた。
英字用のenglishComposingが残り、InputConnectionの未確定領域を次の日本語入力が
setComposingTextで上書きしていた。

confirmAsyncで英字用バッファを先に処理し、入力どおりの英字を既存のcommitTextで
確定する。英字補完の第1候補は採用せず、確定が成功すると英字バッファ・候補・
保留中の候補更新を既存の経路で終了する。

UIは古いComposeフレームのcurrentSurfaceで呼び出しを省略せず、切替前に常に
サービス側の現在の入力を確定させる。空入力のときはサービス側で何もしない。
Windows/shared/nativeの変換処理やモデル、既存の英字Space/候補タップの動作は変更しない。

## 検証

vivoの2.0.8-devで、QWERTY/12キーの `he` / `get` の4ケースすべてが
「切替時に英字を確定できていない」という検査に失敗した。
同じテストAPK（SHA-256 `acbd7ae9565ef813744bd609749521aea46186ea33dab48346d611394ca535c7`）を
2.0.9-devでも使い、全ケースの成功を確認した。

- `scripts/build-android.ps1 -SkipNative`：成功。両ABIのソース/バイナリ指紋、
  依存閉包、CPUバックエンドとAPK内モデルを照合。nativeソース変更なし。
- `:app:testDebugUnitTest` 183件、`:rebuilt:testDebugUnitTest` 79件成功。
- `:rebuilt:lintDebug`：エラー0、警告28。
- V2520A / Android 16 / API 36：基本24項目成功、失敗0。英字Enterの4検査を拡張し、
  補完候補がある状態でのモード切替、英字原文の確定、日本語の追加入力、
  日本語だけの削除、英語へ戻っての追加を確認。カーソル変換・部分確定・文字数も含む。
- クリップボードUI 7項目成功、失敗0。
- 未完ローマ字の10項目もライブ変換ON/OFFで成功、失敗0。
  最初の2回はIME表示待ちで試験が停止し、調査時には別アプリが前面だった。
  端末を操作せず検証画面を保った状態で、同じAPKを再実行して全件成功した。
- 試験で変更する入力設定は復元し、学習書込みを抑止。端末内APKと固定保存APKの
  ハッシュ、versionCode=11 / versionName=2.0.9-devを照合した。

## 成果物

`dist/android/20261026-mode-switch/ZeroConvert-IME-2.0.9-dev-arm64-debug.apk`

- applicationId `com.zeroconvert.ime` / ARM64 / 署名済みdebug。
- SHA-256：`4f0a41407c792ee7bc15ffb045580d5e45fbec62fd425f253b91d6d705cff20e`。
- vivoへ上書き更新して確認した同じAPKを固定保存。従来の未完n保護、クリップボード期限、
  ピン留め除外、カーソル変換と文字数表示の修正も含む。
- モデル情報と検証結果は同フォルダのartifact.jsonとSHA256SUMS、
  ローカルログは `.local/android-mode-switch-20261026/`。

正式release署名、ストア公開、Gitコミット/pushは未実施。
