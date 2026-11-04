"""Explicit, local-only typing recorder. Captures keys only inside its own window."""
import argparse
import hashlib
import json
from pathlib import Path
import time
import tkinter as tk
from tkinter import ttk

from dataset_isolation import normalize
from qwerty_trace import TraceReplay


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out",type=Path,required=True)
    a=p.parse_args(); a.out.parent.mkdir(parents=True,exist_ok=True)
    root=tk.Tk(); root.title("QWERTY打鍵評価の記録")
    replay=TraceReplay(); events=[]; begun=None; latest=None
    fields={}
    ttk.Label(root,text="このウィンドウ内だけを記録します。英数入力に切り替えてローマ字で打鍵してください。\n削除・左右移動も記録します。保存前に意図した読みと動作を確認してください。").pack(padx=16,pady=12)
    for key,title in (("context","直前の文脈"),("surface","意図した文章・表記"),("reading","意図した読み（ひらがな）")):
        ttk.Label(root,text=title).pack(anchor="w",padx=16)
        var=tk.StringVar(); ttk.Entry(root,textvariable=var,width=70).pack(padx=16,pady=3); fields[key]=var
    action=tk.StringVar(value="repair")
    ttk.Label(root,text="repair：タイポを修復 ／ keep：変更しない ／ wait：入力途中").pack(pady=3)
    ttk.Combobox(root,textvariable=action,values=("repair","keep","wait"),state="readonly").pack(pady=6)
    display=tk.StringVar(value="「記録開始」を押し、この欄をクリックして入力")
    area=tk.Label(root,textvariable=display,width=70,height=5,relief="sunken",takefocus=True,wraplength=600)
    area.pack(padx=16,pady=8); status=tk.StringVar(); ttk.Label(root,textvariable=status).pack()
    def start():
        nonlocal begun,latest
        events.clear(); latest=None; begun=time.perf_counter(); display.set(""); area.focus_set(); status.set("記録中")
    def key(event):
        nonlocal latest
        if begun is None: return "break"
        item=None
        if event.keysym=="BackSpace": item={"kind":"backspace"}
        elif event.keysym in ("Left","Right"): item={"kind":"move","offset":-1 if event.keysym=="Left" else 1}
        elif len(event.char)==1 and event.char.isascii() and event.char.isprintable(): item={"kind":"key","key":event.char}
        if item:
            if len(events)>=256: status.set("256イベント上限です。保存または記録し直してください。"); return "break"
            item["ms"]=(time.perf_counter()-begun)*1000; events.append(item)
            latest=replay.replay(events)[-1]
            display.set(latest["native_observed"]+f"\nカーソル: {latest['native_cursor']}")
        return "break"
    def save():
        nonlocal begun
        reading=normalize(fields["reading"].get()); surface=fields["surface"].get()
        if not latest or not latest["observed_keys"] or not reading or not surface: status.set("打鍵と意図した表記・読みを入力してください。"); return
        if action.get()=="repair" and reading==latest["native_observed"]:
            status.set("修復対象の読みが元入力と同じです。keepか、意図した読みを確認してください。"); return
        target=reading if action.get()=="repair" else latest["native_observed"]
        group=hashlib.sha256(reading.encode()).hexdigest()
        record={"id":hashlib.sha256(json.dumps(events,sort_keys=True).encode()).hexdigest(),"source_group":group,
            "context":fields["context"].get(),"reference_surface":surface,"target_reading":target,
            "intended_reading":reading,"expected_action":action.get(),"events":list(events),**latest,
            "provenance":"Human events explicitly recorded in local test window; labels confirmed by recorder operator",
            "human_recorded":True,"independently_adjudicated":False,"native":replay.native.identity}
        with a.out.open("a",encoding="utf-8") as stream: stream.write(json.dumps(record,ensure_ascii=False)+"\n")
        begun=None; status.set("保存しました。次の文章は記録開始から。")
    area.bind("<KeyPress>",key)
    ttk.Button(root,text="記録開始",command=start).pack(side="left",padx=16,pady=12)
    ttk.Button(root,text="確認して保存",command=save).pack(side="right",padx=16,pady=12)
    root.mainloop()


if __name__=="__main__": main()
