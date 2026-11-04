"""New authored phrase groups, frozen before model comparison; synthetic only."""
import argparse
import hashlib
import json
from pathlib import Path
import random

from build_origin_corpus import romanize
from build_qwerty_corrector import mutations, pending
from dataset_isolation import load_jsonl, sha256, source_keys
from evaluate_v4_repair_comparison import ReadingVerifier
from native_qwerty import NativeQwerty

PHRASES = [
    ("雨が止むまで図書館で待ちます", "あめがやむまでとしょかんでまちます"),
    ("冷蔵庫の奥に牛乳があります", "れいぞうこのおくにぎゅうにゅうがあります"),
    ("木曜日の午後に荷物が届きます", "もくようびのごごににもつがとどきます"),
    ("青い封筒を机の上に置きました", "あおいふうとうをつくえのうえにおきました"),
    ("帰り道で新しいパン屋を見つけました", "かえりみちであたらしいぱんやをみつけました"),
    ("洗濯物を取り込んでから出発します", "せんたくものをとりこんでからしゅっぱつします"),
    ("次の交差点を左に曲がってください", "つぎのこうさてんをひだりにまがってください"),
    ("窓際の席を予約してあります", "まどぎわのせきをよやくしてあります"),
    ("工具箱は階段の下にしまいました", "こうぐばこはかいだんのしたにしまいました"),
    ("夕食の前に犬の散歩へ行きます", "ゆうしょくのまえにいぬのさんぽへいきます"),
    ("古い写真を一枚ずつ整理しています", "ふるいしゃしんをいちまいずつせいりしています"),
    ("充電器を旅行用の袋に入れました", "じゅうでんきをりょこうようのふくろにいれました"),
    ("坂道を登ると海が見えてきます", "さかみちをのぼるとうみがみえてきます"),
    ("野菜を細かく切って鍋に加えます", "やさいをこまかくきってなべにくわえます"),
    ("隣の部屋からピアノの音が聞こえます", "となりのへやからぴあののおとがきこえます"),
    ("鍵を玄関の小さな棚に置きました", "かぎをげんかんのちいさなたなにおきました"),
    ("週末は自転車の空気を入れます", "しゅうまつはじてんしゃのくうきをいれます"),
    ("温かいお茶を二人分用意しました", "あたたかいおちゃをふたりぶんよういしました"),
    ("明るいうちに庭の落ち葉を集めます", "あかるいうちににわのおちばをあつめます"),
    ("電池を交換すると時計が動きました", "でんちをこうかんするととけいがうごきました"),
]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out", type=Path, required=True)
    a = p.parse_args()
    if a.out.exists():
        raise FileExistsError(a.out)
    parent = Path("artifacts/datasets/corrector-qwerty-state-20261030-r1")
    reserved = set().union(*(source_keys(r) for s in ("train", "val", "test") for r in load_jsonl(parent/f"{s}.jsonl")))
    native = NativeQwerty()
    verifier = ReadingVerifier(Path("ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin"))
    rows = []
    for surface, reading in PHRASES:
        raw = romanize(reading)
        if native.decode(raw) != reading or not verifier.matches(surface, reading):
            raise ValueError(f"Authored reading needs review: {surface}")
        group = "authored-"+hashlib.sha256(surface.encode()).hexdigest()[:16]
        variants = [(raw, "append", "keep", "clean", surface), (raw+"k", "append", "wait", "pending", native.decode(raw+"k"))]
        for cut in range(len(raw)//2, 1, -1):
            observed = native.decode(raw[:cut])
            if not pending(observed):
                variants.append((raw[:cut], "backspace", "keep", "editing_prefix", observed))
                break
        chosen = set()
        for keys, kind, _ in mutations(raw, random.Random(group)):
            observed = native.decode(keys)
            if kind not in chosen and not pending(observed) and observed != reading:
                variants.append((keys, "append", "repair", kind, surface))
                chosen.add(kind)
        for i, (keys, state, action, kind, target) in enumerate(variants):
            observed = native.decode(keys)
            row = {"id":f"{group}-{i}", "source_group":group, "context":"", "input_reading":observed,
                "native_observed":observed, "observed_keys":keys, "input_state":state, "cursor_key_offset":len(keys),
                "expected":target, "target_reading":reading if action=="repair" else observed,
                "expected_action":action, "is_clean":action!="repair", "typo_type":kind, "acceptable":[],
                "provenance":"New authored phrase; synthetic native typing; deletion state simulated; no human adjudication"}
            if source_keys(row)&reserved:
                raise ValueError("Authored group overlaps existing data")
            rows.append(row)
    a.out.mkdir(parents=True)
    path = a.out/"test.jsonl"
    path.write_text("".join(json.dumps(r,ensure_ascii=False)+"\n" for r in rows),encoding="utf-8")
    (a.out/"manifest.json").write_text(json.dumps({"code_sha256":sha256(__file__), "native":native.identity,
        "rows":len(rows), "groups":len(PHRASES), "sha256":sha256(path),
        "limits":"Authored after training start, before comparison; synthetic, unadjudicated, not user traces or safety evidence"},ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print("Frozen",len(rows),"cases",len(PHRASES),"groups")


if __name__ == "__main__":
    main()
