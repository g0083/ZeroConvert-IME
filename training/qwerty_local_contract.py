"""Single-corrector local key patch: K / W / E:unique-before:after.

Gold keys are used only by make_target, never parse_prediction.
"""
import re
from keyboard_hybrid import NEIGHBORS, is_kana


def one_edit(before, after):
    if before == after: return False
    i=0
    while i<min(len(before),len(after)) and before[i]==after[i]: i+=1
    a,b=before[i:],after[i:]
    if len(a)==len(b):
        return (bool(a) and b[0] in NEIGHBORS.get(a[0],"") and a[1:]==b[1:]) or (len(a)>=2 and a[:2]==b[:2][::-1] and a[2:]==b[2:])
    return (len(a)+1==len(b) and a==b[1:]) or (len(a)==len(b)+1 and a[1:]==b)


def make_target(row):
    action=row["expected_action"]
    if action=="keep": return "K"
    if action=="wait": return "W"
    before,after=row["observed_keys"],row["canonical_keys"]
    if not one_edit(before,after): raise ValueError("Expected one physical edit")
    start=0
    while start<min(len(before),len(after)) and before[start]==after[start]: start+=1
    end_a,end_b=len(before),len(after)
    while end_a>start and end_b>start and before[end_a-1]==after[end_b-1]: end_a-=1; end_b-=1
    for width in range(max(3,end_a-start),13):
        for left in range(max(0,end_a-width),min(start,len(before)-width)+1):
            right=left+width
            old=before[left:right]
            if left<=start and right>=end_a and before.find(old)==before.rfind(old):
                new=before[left:start]+after[start:end_b]+before[end_a:right]
                return f"E:{old}:{new}"
    raise ValueError("No unique bounded anchor")


def parse_prediction(text, observation, native):
    if text in ("K","W"): return {"action":text,"valid":True,"reading":None}
    keys=observation.get("observed_keys")
    match=re.fullmatch(r"E:([a-z-]{1,12}):([a-z-]{0,14})",text)
    if not keys or not match or not re.fullmatch(r"[a-z-]{1,96}",keys):
        return {"action":"invalid","valid":False,"reading":None}
    old,new=match.groups()
    if old not in keys or keys.find(old)!=keys.rfind(old): return {"action":"invalid","valid":False,"reading":None}
    corrected=keys.replace(old,new,1)
    if not 1<=len(corrected)<=96 or not one_edit(keys,corrected):
        return {"action":"invalid","valid":False,"reading":None}
    reading=native.decode(corrected)
    if not is_kana(reading): return {"action":"invalid","valid":False,"reading":None}
    return {"action":"E","valid":True,"reading":reading,"keys":corrected}
