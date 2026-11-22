# ZeroConvert IME — Keyboard Promo Film

キーボードのアピールポイントを高度なグラフィック表現で紹介する 52 秒のプロモーション映像。

## 成果物

| ファイル | 内容 |
|---|---|
| `ZeroConvert-IME-Keyboard-Film.mp4` | 1920×1080 / 24fps / 52s / H.264 (字幕焼き込み) |
| `index.html` | インタラクティブ版（再生・シーク・シーン切替付き）。`?clean=1` でプレイヤーを隠したクリーン表示 |
| `poster-intro.jpg` / `poster-keyboard.jpg` | ポスター画像 |

## 構成

- 0:00–0:07 INTRO：変換操作をなくす新しいキーボード
- 0:07–0:15 ライブ変換＋自動確定（16字以上の安定・Backspace巻き戻し）
- 0:15–0:23 タイポ補正（okseri → おかえり、keyboard-prior、学習3回で自動反映）
- 0:23–0:31 先読み（よろしくお願いし → ます。）
- 0:31–0:42 キーボード本体（12キー/QWERTY、フローティング/片手/分割、ZeroCon AI候補バー、テーマ6種、振動）
- 0:42–0:46 オンデバイス＋プライベートモード
- 0:46–0:52 OUTRO：Windows / Android ダウンロード

## 再生

- MP4 をそのまま開く（音声なし・字幕付き）。
- `index.html` をブラウザで開くと、シーンジャンプと再生/一時停止で確認できます。

## 再生成

`index.html` の `window.ZEROCON_VIDEO.seek(t, true)` で任意時刻を描画する。MP4化は Chrome＋ffmpeg のフレームキャプチャ（24fps）。
