# -*- coding: utf-8 -*-
import urllib.request
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

test_prompts = [
    ("業務が終了しました。同僚に向かって、お疲れ様", "期待: です / でした"),
    ("プロジェクトの進捗について、機能の実装が完了", "期待: しました / いたしました"),
    ("明日のミーティングですが、ご都合のよろしい", "期待: 日時 / 日程をお知らせください"),
    ("外は雨が激しく降っているので、傘を持って", "期待: いったほうが / いきましょう"),
    ("GitHubの修正コミットについて、プルリクエストを", "期待: 作成しました / レビューしてください"),
    ("取引先への挨拶として、いつもお世話に", "期待: なっております")
]

print("=== LFM 230M & 350M Text Completion による純AI先読み ===")
for model in ["lfm2.5-230m@q4_k_m", "liquidai/lfm2.5-350m"]:
    print(f"\n>> モデル: {model}")
    for p, exp in test_prompts:
        req = urllib.request.Request(
            "http://127.0.0.1:1234/v1/completions",
            headers={"Content-Type": "application/json"},
            data=json.dumps({
                "model": model,
                "prompt": p,
                "max_tokens": 12,
                "temperature": 0.0,
                "stop": ["\n", "。", "！", "!"]
            }).encode("utf-8")
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                res = json.loads(resp.read().decode("utf-8"))
                out = res["choices"][0]["text"].strip()
                print(f"  入力: 「{p[-15:]}」 -> AI先読み: [{out}] ({exp})")
        except Exception as e:
            print(f"  Error: {e}")
