# Androidの矢印キーによるカーソル移動時のひらがな化・意図しない確定の修正（2.0.11-dev）

2026-10-29、ライブ変換ONの未確定入力中に矢印キーを押すとプレビューが解除されて全文ひらがな化する問題、および連続移動時に全文が確定されてしまう報告に対応した。

## 原因と修正

1. **未確定範囲内カーソル移動の外部確定誤判定の修正**:
   - `onUpdateSelection` では、IME自身の選択変更履歴と厳密に一致しない通知が届いた際、カーソルが末尾以外の場所にある（`newSelStart != candidatesEnd`）というだけで外部操作とみなして `clearInput()` および `finishComposingText()` を呼んでいた。
   - カーソルが未確定テキストの範囲内（`candidatesStart..candidatesEnd`）にある場合や、IMEがキー操作中（`managedCursorKeys`）の場合は外部移動とみなさないよう判定条件を修正した。これにより、未確定入力の途中で矢印キーを何度動かしても意図せず確定されることがなくなった。

2. **エディタ側 selectionEnd の未更新によるオフセット狂いと選択スキップの修正**:
   - `ComposingEditorState.write` において、`beginBatchEdit` 中にエディタが返す `selectionEnd()` が直前の古いテキスト長のオフセットを返していた場合、`it - text.length` の計算が狂って `startHint` を破壊し、キャレットが意図した文字境界ではなく末尾（`体調かく|` 等）に取り残される原因となっていた。
   - すでに記録されている `startHint` を優先して利用し、未更新の selectionEnd で開始オフセットが狂わないようにした。

3. **矢印キー移動時のプレビュー維持**:
   - `updateComposingDisplayOnCursorMove()` において、矢印キーを押した瞬間に全文を無条件で読み（`convertTarget`）に戻していたため、変換された漢字が一瞬でひらがなに化けていた。
   - 現在のプレビューの末尾が移動後の残り読み suffix と一致する場合（例: 「東京にいく」で「にいく」の前に移動）、プレビュー文字列を維持したままキャレットを移動するようにした。また、漢字の途中にカーソルが入って読みに戻る場合でも、前後のオフセットと再変換結果が崩れず安全に維持される。

## テストと検証結果

- `ComposingEditorStateTest.kt` に、古い selectionEnd の影響を受けずに正しいカーソル位置を設定できることを検証するテスト `writePrefersExistingStartHintOverStaleSelectionEnd`、および連続移動時の整合性を検証する `writeSupportsMultipleConsecutiveLeftMovesWithoutCorruption` を追加。
- `:app:testDebugUnitTest` 188件成功。
- `:rebuilt:testDebugUnitTest` 82件成功。
- `:app:lintDebug`、`:rebuilt:lintDebug`: エラー 0。
- `scripts/build-android.ps1 -SkipNative`: 成功。
  - `rebuilt-arm64-v8a-debug.apk`: SHA-256 `0675A2B3F9B81388AD713F8E9A99F73A0320F3BECC5A01399DBE3A5880046691`
  - `rebuilt-x86_64-debug.apk`: SHA-256 `7CCD42DEE9CD922BFBA7659477BEEB09E7CBBE458F6AB9E65B344467C8D106D2`
