"""共通候補生成 840問 × 18モデルの散布図（X: 実行時間, Y: 正答率）生成器。

入力: --outdir の summary.json（run_common_candidates.py の出力）
出力: --outdir/scatter.png, --outdir/scatter.html, --outdir/plot_summary.json

使い方:
  python scripts/run.py benchmarks/common/run_common_candidates_plot.py \\
    --outdir artifacts/results/common-candidates-840-10models-20261123

別測定を同じ図に重ねる（例: v4.2 を新エンジンで測り直した点を★で追加）:
  python scripts/run.py benchmarks/common/run_common_candidates_plot.py --outdir artifacts/results/common-candidates-840-18models-20261123 --overlay artifacts/results/common-candidates-840-18models-newengine-20261123/summary.json

散布図の凡例は ZeroCon v4.2 / v4.1 / v4 / v3 / Zenz v3.2 （マーカー色分け）。
ラベル横の (Ns / pp%) は 壁時間 / 正答率。壁時間はドライバ計測
（起動〜840問完了、平均レイテンシ×840＋起動・転送を含む）。
"""
import argparse
import base64
import html
import io
import json
import math
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager


def setup_fonts():
    # Windows の日本語フォントを優先登録する（無ければ DejaVu のままだと日本語が豆腐になる）。
    for name in ("Noto Sans JP", "Meiryo", "Yu Gothic", "Hiragino Sans"):
        names = {f.name for f in font_manager.fontManager.ttflist}
        if name in names:
            plt.rcParams["font.family"] = name
            return name
    return plt.rcParams["font.family"]


def family(mid: str):
    if mid.startswith("zenz-v3.2"):
        return "zenz"
    if "v4.2" in mid:
        return "v42"
    if "v4.1" in mid:
        return "v41"
    if "v4" in mid:
        return "v4"
    if "v3" in mid:
        return "v3"
    return "other"


FAM_STYLE = {
    "v42": ("#0f7a5a", "o", "ZeroCon v4.2"),
    "v41": ("#2f6cb0", "s", "ZeroCon v4.1"),
    "v4": ("#6a4c9c", "D", "ZeroCon v4"),
    "v3": ("#b38800", "h", "ZeroCon v3"),
    "zenz": ("#777777", "^", "Zenz v3.2（学習なし）"),
}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--outdir", type=Path,
                    default=Path("artifacts/results/common-candidates-840-18models-20261123"))
    ap.add_argument("--overlay", type=Path, default=None,
                    help="同じ図に重ねる別測定の summary.json（例: 新エンジンでの再測）")
    ap.add_argument("--overlay-filter", default="v4.2",
                    help="overlay から重ねるモデルIDの部分一致（空文字で全部）")
    ap.add_argument("--overlay-label", default="v4.2 × 新エンジン",
                    help="凡例に使う名前")
    ap.add_argument("--overlay-tag", default="★新エンジン",
                    help="各点のラベル先頭に付ける短い識別子")
    args = ap.parse_args()
    setup_fonts()

    summary = json.loads((args.outdir / "summary.json").read_text(encoding="utf-8"))
    models = [m for m in summary["models"] if m.get("count", 0) > 0]
    # X で小さい順に並べ、ラベルの配置を決めやすくする
    models.sort(key=lambda m: m["wall_s"])
    cond = summary["condition"]

    overlay_models = []
    if args.overlay:
        osum = json.loads(args.overlay.read_text(encoding="utf-8"))
        overlay_models = [m for m in osum["models"]
                          if m.get("count", 0) > 0
                          and (not args.overlay_filter or args.overlay_filter in m["model_id"])]
        overlay_models.sort(key=lambda m: m["wall_s"])
    plt.rcParams.update({"axes.unicode_minus": False, "figure.dpi": 150})

    fig, ax = plt.subplots(figsize=(12, 7.2))
    fig.patch.set_facecolor("white")
    ax.set_facecolor("white")

    # グリッド & 軸範囲（スコープごとに壁時間の幅が違うので動的に決める）
    all_pts = models + overlay_models
    x_max = max(m["wall_s"] for m in all_pts)
    ax.set_xlim(0, (int(x_max * 1.15 / 10) + 1) * 10)
    y_min = min(m["accuracy"] for m in all_pts)
    ax.set_ylim(int(y_min // 5 * 5) - 5, 102)
    ax.grid(True, which="major", color="#e6e6e6", linewidth=0.8, linestyle="-")
    ax.grid(True, which="minor", color="#f2f2f2", linewidth=0.6)
    ax.set_axisbelow(True)

    scope_title = summary.get("scope_title", "共通候補生成ベンチマーク（840問）")
    is_model_scope = "scope" in summary and str(summary["scope"]).startswith("model")
    ax.set_xlabel("実行時間（840問の壁時計：起動〜完了）[秒] — 小さいほど高速", fontsize=12)
    ax.set_ylabel("ベンチマーク正答率（top1, 840問）[%] — 高いほど正確", fontsize=12)
    ax.set_title(f"{scope_title}: 実行時間 × 正答率",
                 fontsize=15, fontweight="bold", pad=12)
    server_note = ("試験環境 keyboard-prior / llama（モデル直結、N-best なし）"
                   if is_model_scope else "試験環境 azookey-server / llama / Dictionary")
    gpu_layers = int(cond.get("gpu_layers", 0) or 0)
    gpu_note = (f"Vulkan（gpu_layers={gpu_layers}・全層オフロード）" if gpu_layers > 0
                else "CPU 同条件（gpu_layers=0）")
    note_lines = ["散布は 壁時間（起動〜840問）× top1 正答率。色は v4.2 / v4.1 / v4 / v3 / Zenz。"]
    if overlay_models:
        note_lines.insert(0, f"{args.overlay_tag} は同じモデルを {args.overlay_label} で測り直した点。")
    ax.text(0.99, 0.02,
            f"{gpu_note}, {server_note}, 入力順逐次\n" + "\n".join(note_lines),
            ha="right", va="bottom", transform=ax.transAxes, fontsize=8, color="#555555")

    # ラベルは衝突回避で自動配置する。点の密集度はスコープや速度で変わるため、
    # 手動オフセットではなく画面上の矩形で空きを探す。
    font_pt = 7.2 if is_model_scope else 7.8
    renderer = fig.canvas.get_renderer()

    def overlaps(a, b, pad=2.0):
        return not (a.x1 + pad < b.x0 or b.x1 + pad < a.x0
                    or a.y1 + pad < b.y0 or b.y1 + pad < a.y0)

    # 矩形は画面座標で扱うので、先にレイアウトを確定させる。
    fig.tight_layout()

    # 凡例を先に置き、ラベルはその領域も避ける。
    from matplotlib.lines import Line2D
    legend_elems = [Line2D([0], [0], marker=marker, color="w", markerfacecolor=color,
                           markeredgecolor="white", markersize=11, label=name)
                    for _, (color, marker, name) in FAM_STYLE.items()]
    if overlay_models:
        legend_elems.append(Line2D([0], [0], marker="*", color="w", markerfacecolor="#444444",
                                   markeredgecolor="white", markersize=16,
                                   label=f"★ {args.overlay_label}"))
    ax.legend(handles=legend_elems, loc="lower right" if is_model_scope else "lower left",
              bbox_to_anchor=(0.99, 0.06) if is_model_scope else None,
              fontsize=10, framealpha=0.95, title="モデル系統")

    pts = []
    for m in models:
        color, marker, _ = FAM_STYLE[family(m["model_id"])]
        pts.append({"m": m, "color": color, "marker": marker, "size": 150,
                    "edge": "white", "lw": 1.4, "tag": "", "zorder": 5, "half": 10})
    for m in overlay_models:
        color, _, _ = FAM_STYLE[family(m["model_id"])]
        pts.append({"m": m, "color": color, "marker": "*", "size": 460,
                    "edge": "#333333", "lw": 0.8, "tag": args.overlay_tag,
                    "zorder": 8, "half": 13})

    occupied = [ax.get_legend().get_window_extent(renderer=renderer)]
    for pt in pts:
        m = pt["m"]
        ax.scatter([m["wall_s"]], [m["accuracy"]], s=pt["size"], c=pt["color"],
                   marker=pt["marker"], edgecolors=pt["edge"], linewidths=pt["lw"],
                   zorder=pt["zorder"])
        px, py = ax.transData.transform((m["wall_s"], m["accuracy"]))
        occupied.append(type("R", (), {"x0": px - pt["half"], "x1": px + pt["half"],
                                       "y0": py - pt["half"], "y1": py + pt["half"]})())
    axes_box = ax.get_window_extent(renderer=renderer)

    # ラベル1行分の大きさを測り、候補位置の矩形は解析的に求める。
    probe = ax.annotate("あ\nあ", (0.5, 0.5), xycoords="axes fraction",
                        ha="left", va="center", fontsize=font_pt)
    ref = probe.get_window_extent(renderer=renderer)
    probe.remove()
    line_w, line_h = ref.width / 2.0, ref.height / 2.0
    pad_px = 3.0

    # 候補位置: 点の周囲を放射状に、近い半径から。距離は画面ピクセル基準。
    radii = list(range(14, 60, 8)) + list(range(60, 240, 14))
    angles = [0, 180, 90, 270, 30, 150, 210, 330, 60, 120, 240, 300]

    for pt in pts:
        m = pt["m"]
        x, y = m["wall_s"], m["accuracy"]
        head = f"{m['short']} {pt['tag']}" if pt["tag"] else m["short"]
        label = f"{head}\n{x:.1f}秒 / {y:.1f}% ({m['correct']}/{m['count']})"
        lines = label.split("\n")
        w = max(len(t) for t in lines) * line_w + 2 * pad_px
        h = len(lines) * line_h + 2 * pad_px
        px, py = ax.transData.transform((x, y))
        best = None
        for r in radii:
            for ang in angles:
                rad = math.radians(ang)
                ax_px, ay_px = px + r * math.cos(rad), py + r * math.sin(rad)
                ha = "left" if math.cos(rad) > 0.3 else ("right" if math.cos(rad) < -0.3 else "center")
                x0 = ax_px if ha == "left" else (ax_px - w if ha == "right" else ax_px - w / 2.0)
                box = type("R", (), {"x0": x0, "x1": x0 + w, "y0": ay_px - h / 2.0,
                                     "y1": ay_px + h / 2.0})()
                inside = (box.x0 >= axes_box.x0 + 2 and box.x1 <= axes_box.x1 - 2
                          and box.y0 >= axes_box.y0 + 2 and box.y1 <= axes_box.y1 - 2)
                if inside and not any(overlaps(box, o) for o in occupied):
                    best = (ax_px - px, ay_px - py, ha, box)
                    break
            if best:
                break
        if best is None:  # 置けなければ右下へ退避し、重なりは許容する。
            ax_px, ay_px = px + 12, py - 16
            best = (12, -16, "left", type("R", (), {"x0": ax_px, "x1": ax_px + w,
                                                    "y0": ay_px - h / 2.0, "y1": ay_px + h / 2.0})())
        dx_px, dy_px, ha, box = best
        ax.annotate(label, (x, y), textcoords="offset points",
                    xytext=(dx_px * 72.0 / fig.dpi, dy_px * 72.0 / fig.dpi), ha=ha, va="center",
                    fontsize=font_pt, color="#111111" if pt["tag"] else "#222222",
                    bbox=dict(boxstyle="round,pad=0.28",
                              fc="#fffdf0" if pt["tag"] else "white",
                              ec="#c9a227" if pt["tag"] else "#cccccc",
                              lw=1.1 if pt["tag"] else 1.0, alpha=0.95),
                    arrowprops=dict(arrowstyle="-", color="#999999", linewidth=0.7),
                    zorder=7 if pt["tag"] else 6)
        occupied.append(box)

    png = args.outdir / "scatter.png"
    fig.savefig(png, dpi=200, bbox_inches="tight")
    plt.close(fig)

    # HTML（PNG 埋め込み + 表 + 部分集合）: 直下の PNG 単独でも開ける
    b64 = base64.b64encode(png.read_bytes()).decode()
    rows = sorted(models, key=lambda m: (-m["accuracy"], m["wall_s"]))
    trs = []
    for m in rows:
        trs.append(
            "<tr>"
            f"<td>{html.escape(m['display_name'])}<br><small>{html.escape(m['model_id'])}</small></td>"
            f"<td>{m['count']}</td><td>{m['correct']}</td>"
            f"<td><strong>{m['accuracy']:.1f}%</strong></td>"
            f"<td>{m['wall_s']:.1f}</td><td>{m['load_s']:.1f}</td>"
            f"<td>{m['mean_latency_ms']:.1f}</td>"
            f"<td>{m['p50_ms']:.1f} / {m['p95_ms']:.1f} / {m['p99_ms']:.1f}</td>"
            f"<td>{m['mean_nbest_ms']:.1f}</td>"
            f"<td>{m['model_bytes'] / 1024 / 1024:.1f}</td>"
            "</tr>")
    overlay_trs = []
    for m in sorted(overlay_models, key=lambda m: (-m["accuracy"], m["wall_s"])):
        overlay_trs.append(
            "<tr style=\"background:#fffdf0\">"
            f"<td>★ {html.escape(m['display_name'])}<br>"
            f"<small>{html.escape(args.overlay_label)}: {html.escape(m['model_id'])}</small></td>"
            f"<td>{m['count']}</td><td>{m['correct']}</td>"
            f"<td><strong>{m['accuracy']:.1f}%</strong></td>"
            f"<td>{m['wall_s']:.1f}</td><td>{m['load_s']:.1f}</td>"
            f"<td>{m['mean_latency_ms']:.1f}</td>"
            f"<td>{m['p50_ms']:.1f} / {m['p95_ms']:.1f} / {m['p99_ms']:.1f}</td>"
            f"<td>{m['mean_nbest_ms']:.1f}</td>"
            f"<td>{m['model_bytes'] / 1024 / 1024:.1f}</td>"
            "</tr>")
    subset_trs = []
    for m in sorted(models, key=lambda m: m["wall_s"]):
        bs = m.get("by_subset", {})
        cells = [f"{s}: {bs.get(s, {}).get('correct', 0)}/{bs.get(s, {}).get('count', 0)} ({bs.get(s, {}).get('accuracy', 0):.1f}%)"
                 for s in ("basic", "hard", "realistic", "colloquial", "additional_dict")]
        subset_trs.append(f"<tr><td>{html.escape(m['short'])}</td><td>{'</td><td>'.join(html.escape(c) for c in cells)}</td></tr>")

    html_doc = f"""<!doctype html>
<html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>共通候補生成ベンチマーク — 実行時間 × 正答率</title>
<style>
body{{font-family:"Noto Sans JP","Yu Gothic",Hiragino Sans,Meiryo,sans-serif;margin:24px;color:#222}}
img{{max-width:100%;height:auto;border:1px solid #ddd}}
table{{border-collapse:collapse;width:100%;font-size:13px;margin-top:12px}}
th,td{{border:1px solid #ccc;padding:6px 8px;text-align:right}}
th{{background:#f4f6f8;text-align:center}}
td:first-child{{text-align:left}}
small{{color:#666}}
caption{{text-align:left;font-weight:bold;margin:8px 0 4px}}
</style></head><body>
<h2>共通候補生成ベンチマーク（840問）: 実行時間 × 正答率</h2>
<p>横軸は 840 問の壁時計（起動〜完了、ドライバ計測）、縦軸は top1 正答率。CPU 同条件（gpu_layers=0）で入力順逐次。
右上ほど「速く・正確」。N-best 部分（辞書追加検索）の平均は表の末尾に別掲。</p>
<img src="data:image/png;base64,{b64}" alt="実行時間×正答率の散布図">
<table>
<caption>集計表（正答率降順）</caption>
<tr><th>モデル</th><th>問</th><th>正解</th><th>正答率</th><th>壁時間[秒]</th><th>起動[秒]</th>
<th>平均[ms]</th><th>p50 / p95 / p99 [ms]</th><th>N-best平均[ms]</th><th>サイズ[MiB]</th></tr>
{''.join(trs)}
{''.join(overlay_trs)}
</table>
<table>
<caption>部分集合別正解（壁時間昇順） basic 230 / hard 250 / realistic 160 / colloquial 100 / additional_dict 100</caption>
<tr><th>短縮名</th><th>basic</th><th>hard</th><th>realistic</th><th>colloquial</th><th>additional_dict</th></tr>
{''.join(subset_trs)}
</table>
<p style="font-size:12px;color:#555">
条件: サーバ {html.escape(str(cond.get('server','')))} / ランタイム {html.escape(str(cond.get('runtime','')))} / 辞書 {html.escape(str(cond.get('dictionary','')))}。
采点は Rust score_conversion と同一規則（top1 の exact/whitespace/kana/forbidden）。詳細は summary.json / *.jsonl を参照。
</p>
</body></html>"""
    html_path = args.outdir / "scatter.html"
    html_path.write_text(html_doc, encoding="utf-8")

    plot_summary = {"png": str(png), "html": str(html_path),
                    "x": "wall_s (seconds, 840Q startup-to-finish)", "y": "accuracy (% top1, 840Q)",
                    "overlay": str(args.overlay) if args.overlay else None,
                    "overlay_label": args.overlay_label if overlay_models else None,
                    "points": [{"model_id": m["model_id"], "short": m["short"], "wall_s": m["wall_s"],
                                "accuracy": m["accuracy"], "correct": m["correct"], "count": m["count"],
                                "mean_latency_ms": m["mean_latency_ms"],
                                "overlay": any(m is o for o in overlay_models)}
                               for m in sorted(all_pts, key=lambda m: m["wall_s"])]}
    (args.outdir / "plot_summary.json").write_text(json.dumps(plot_summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"png: {png}", flush=True)
    print(f"html: {html_path}", flush=True)


if __name__ == "__main__":
    raise SystemExit(main())
