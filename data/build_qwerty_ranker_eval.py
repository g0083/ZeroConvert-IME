"""Prospective authored event regression, separated from all existing parent groups.

These are natural sentences with synthetic edits, NOT recorded human typing.
The test split remains unevaluated until an explicit policy lock is supplied.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import random

from build_origin_corpus import romanize
from build_qwerty_corrector import mutations, pending
from dataset_isolation import evaluation_keys, load_jsonl, sha256, source_keys
from qwerty_corrector_policy import write_json
from qwerty_reading_search import ReadingSearch
from qwerty_trace import TraceReplay, generate_candidates


PHRASES=[
    ("傘を忘れたので駅まで迎えに来てください","かさをわすれたのでえきまでむかえにきてください","sentence"),
    ("明日の朝はいつもより早く家を出ます","あしたのあさはいつもよりはやくいえをでます","sentence"),
    ("会議室の照明を消してから帰りました","かいぎしつのしょうめいをけしてからかえりました","sentence"),
    ("注文した本が今日の夕方に届きます","ちゅうもんしたほんがきょうのゆうがたにとどきます","sentence"),
    ("冷たい水を少しずつ加えて混ぜます","つめたいみずをすこしずつくわえてまぜます","sentence"),
    ("新しい靴で長い坂道を歩きました","あたらしいくつでながいさかみちをあるきました","sentence"),
    ("窓を閉めても外の工事の音が聞こえます","まどをしめてもそとのこうじのおとがきこえます","sentence"),
    ("借りた本は来週の金曜日に返します","かりたほんはらいしゅうのきんようびにかえします","sentence"),
    ("写真の保存先をもう一度確認します","しゃしんのほぞんさきをもういちどかくにんします","sentence"),
    ("画面の右上に小さな通知が出ています","がめんのみぎうえにちいさなつうちがでています","sentence"),
    ("説明書を読んでから部品を組み立てます","せつめいしょをよんでからぶひんをくみたてます","sentence"),
    ("夕方になると川沿いの道が混みます","ゆうがたになるとかわぞいのみちがこみます","sentence"),
    ("札幌駅で友人と待ち合わせます","さっぽろえきでゆうじんとまちあわせます","proper_noun"),
    ("中村さんに予定の変更を伝えました","なかむらさんによていのへんこうをつたえました","proper_noun"),
    ("金沢の美術館へ電車で向かいます","かなざわのびじゅつかんへでんしゃでむかいます","proper_noun"),
    ("高橋先生から資料を受け取りました","たかはしせんせいからしりょうをうけとりました","proper_noun"),
    ("横浜の支店に電話をかけます","よこはまのしてんにでんわをかけます","proper_noun"),
    ("奈良公園で鹿の写真を撮りました","ならこうえんでしかのしゃしんをとりました","proper_noun"),
    ("小さな箱に切手をしまいました","ちいさなはこにきってをしまいました","alias"),
    ("静かな場所で昼食を食べます","しずかなばしょでちゅうしょくをたべます","alias"),
    ("調子がよければ自転車で出かけます","ちょうしがよければじてんしゃででかけます","alias"),
    ("写真を撮って住所を知らせます","しゃしんをとってじゅうしょをしらせます","alias"),
    ("荷物を持って地下鉄に乗ります","にもつをもってちかてつにのります","alias"),
    ("友人と一緒に出張の準備をします","ゆうじんといっしょにしゅっちょうのじゅんびをします","alias"),
]


def key_events(raw): return [{"kind":"key","key":c} for c in raw]


def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument("--out",type=Path,required=True); a=p.parse_args()
    if a.out.exists(): raise FileExistsError(a.out)
    parent=Path("artifacts/datasets/corrector-qwerty-local-20261030-r4")
    reserved=evaluation_keys()
    for s in ("train","val","test"):
        reserved |= set().union(*(source_keys(r) for r in load_jsonl(parent/f"{s}.jsonl")))
    reserved |= set().union(*(source_keys(r) for r in load_jsonl("artifacts/datasets/corrector-qwerty-fresh-20261030-r1/test.jsonl")))
    replay=TraceReplay(); generator=ReadingSearch(24); sets={"dev":[],"test":[]}; exclusions=[]
    for surface,reading,category in PHRASES:
        raw=romanize(reading); trace=replay.replay(key_events(raw))
        if trace[-1]["native_observed"]!=reading: raise ValueError("Authored reading failed native roundtrip: "+surface)
        group="prospective-"+hashlib.sha256(reading.encode()).hexdigest()[:16]
        split="dev" if int(hashlib.sha256(("20261103:"+group).encode()).hexdigest(),16)%3==0 else "test"
        variants=[(key_events(raw),"keep","clean")]
        # Alternate roman spellings must produce exactly the same native reading.
        alias=raw.replace("shi","si").replace("chi","ti").replace("tsu","tu").replace("sha","sya").replace("shu","syu").replace("sho","syo")
        if alias!=raw and replay.replay(key_events(alias))[-1]["native_observed"]==reading:
            variants.append((key_events(alias),"keep","alias"))
        variants += [(key_events(raw)+[{"kind":"backspace"}],"keep","backspace"),
                     (key_events(raw)+[{"kind":"move","offset":-1}],"keep","cursor"),
                     (key_events(raw+"k"),"wait","pending")]
        for cut in range(len(raw)//2,1,-1):
            if not pending(replay.replay(key_events(raw[:cut]))[-1]["native_observed"]):
                variants.append((key_events(raw[:cut]),"keep","prefix")); break
        kinds=set()
        for keys,kind,_ in mutations(raw,random.Random(group)):
            observed=replay.replay(key_events(keys))[-1]["native_observed"]
            if kind not in kinds and observed!=reading and not pending(observed):
                variants.append((key_events(keys),"repair",kind)); kinds.add(kind)
        for i,(events,action,kind) in enumerate(variants):
            snapshots=replay.replay(events); obs=snapshots[-1]
            target=reading if action=="repair" else obs["native_observed"]
            row={"id":f"{group}-{i}","source_group":group,"context":"",**obs,
                "input_reading":obs["native_observed"],"expected_action":action,"target_reading":target,
                "expected":surface if action=="repair" else obs["native_observed"],"acceptable":[],
                "category":category,"typo_type":kind,"events":events,"snapshots":snapshots,
                "provenance":"Authored natural sentence, synthetic ordered edits; unadjudicated; not human recording"}
            if source_keys(row)&reserved:
                exclusions.append(row["id"]); continue
            row["candidates"]=generate_candidates(generator,row)
            sets[split].append(row)
    # Latin/mixed inputs are preservation cases, unsupported edits abstain.
    for i,raw in enumerate(("GitHub","ZeroConvert","OpenAI","USB3","test123","C:/work","v4.2","example.com")):
        events=key_events(raw); snapshots=replay.replay(events); obs=snapshots[-1]
        row={"id":f"literal-{i}","source_group":f"literal-{raw}","context":"",**obs,
            "input_reading":obs["native_observed"],"expected_action":"keep","target_reading":obs["native_observed"],
            "expected":obs["native_observed"],"category":"literal","typo_type":"literal","events":events,"snapshots":snapshots,
            "provenance":"Synthetic ordered ASCII typing; not human recording"}
        if source_keys(row)&reserved: exclusions.append(row["id"]); continue
        row["candidates"]=generate_candidates(generator,row); sets["dev" if i%3==0 else "test"].append(row)
    a.out.mkdir(parents=True)
    outputs={}
    for split,rows in sets.items():
        path=a.out/f"{split}.jsonl"
        path.write_text("".join(json.dumps(r,ensure_ascii=False)+"\n" for r in rows),encoding="utf-8")
        outputs[split]={"sha256":sha256(path),"rows":len(rows),"groups":len({r['source_group'] for r in rows}),
            "actions":dict(Counter(r['expected_action'] for r in rows)),"types":dict(Counter(r['typo_type'] for r in rows))}
    write_json(a.out/"manifest.json",{"scope":"Prospective synthetic event regression; test results must not select model/policy; human locked evaluation still missing", 
        "outputs":outputs,"exclusions":exclusions,"source_sha256":sha256(__file__),"native":replay.native.identity})
    print(json.dumps(outputs,ensure_ascii=False),flush=True)


if __name__=="__main__": main()
