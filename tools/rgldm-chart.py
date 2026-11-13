#!/usr/bin/env python3
"""Decision map for RGLDM matching: flat KD-tree vs blocked brute force over descriptors x search radius.

    tools/rgldm-chart.py bench-mac.tsv bench-cluster.tsv -o rgldm-decision.svg

Reads TSVs written by tools/rgldm-bench.sh, one panel per machine (host + cpu). Each cell is an (x, radius) configuration
colored by log2(brute-force time / tree time): blue = tree faster, red = brute force faster, gray = tie. The x-axis is the
number of descriptors per side, or the number of neighbors when the TSV sweeps neighbors (--x to force one).
Standard library only, output is SVG.
"""
import argparse, csv, math, sys
from collections import defaultdict

TREE, BRUTE = "FLAT_KDTREE", "BLOCKED_BRUTE_FORCE"
SURFACE, INK, INK2, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#dddcd7"
MID = "#f0efec"                       # neutral midpoint: a tie
BLUE_POLE, RED_POLE = "#2a78d6", "#d03b3b"  # tree faster / brute force faster
SCALE = {"neg": 3.0, "pos": 3.0}      # log2 extent of the color scale per side; set from the data in render()


def hex_to_rgb(h): return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))
def rgb_to_hex(c): return "#%02x%02x%02x" % tuple(max(0, min(255, round(v))) for v in c)
def mix(a, b, t): return tuple(x + (y - x) * t for x, y in zip(hex_to_rgb(a), hex_to_rgb(b)))


def cell_color(log_ratio):
    ext = SCALE["pos"] if log_ratio > 0 else SCALE["neg"]
    t = min(1.0, abs(log_ratio) / ext) if ext > 0 else 0.0
    return rgb_to_hex(mix(MID, BLUE_POLE if log_ratio > 0 else RED_POLE, t))




def fmt_ratio(r):
    return ("%.1f×" if r < 10 else "%.0f×") % r


def fmt_radius(r):
    return "none" if r == 0 else ("%g" % r)


def load(paths):
    rows = []
    for p in paths:
        with open(p, newline="") as f:
            for row in csv.DictReader(f, delimiter="\t"):
                rows.append(row)
    return rows


def x_mode(rows, forced):
    if forced != "auto":
        return forced
    return "neighbors" if len({r["neighbors"] for r in rows}) > 1 else "descriptors"


def panels(rows, mode):
    """machine -> {(x, radius): {method: medianMs}} plus metadata; x = descriptorsB or neighbors"""
    by = defaultdict(lambda: {"cells": defaultdict(dict), "meta": None, "nA": {}, "mode": mode})
    for r in rows:
        key = (r["host"], r["cpu"])
        d, rad = int(r["neighbors"] if mode == "neighbors" else r["descriptorsB"]), float(r["radius"])
        p = by[key]
        p["cells"][(d, rad)][r["method"]] = float(r["medianMs"])
        p["nA"][(d, rad)] = (int(r["nA"]), int(r["nB"]), float(r["inRadiusAvg"]), r["candidates"], r["sameAsFirst"])
        p["meta"] = r
    return by


def esc(s): return str(s).replace("&", "&amp;").replace("<", "&lt;")


def render(by, out):
    cell_w, cell_h, left, top, gap, none_gap = 96, 44, 120, 84, 40, 10
    pan = []
    for (host, cpu), p in sorted(by.items()):
        xs = sorted({d for d, _ in p["cells"]})
        rs = {r for _, r in p["cells"]}
        ys = sorted(r for r in rs if r > 0) + ([0] if 0 in rs else [])   # radii ascending, "none" (unrestricted) as a separate band at the bottom
        pan.append((host, cpu, p, xs, ys))
    if not pan:
        sys.exit("no rows")
    # color scale spans the extremes of all panels: left end = largest brute-force advantage, right end = largest tree advantage
    lrs = [math.log2(t[BRUTE] / t[TREE]) for _, _, p, _, _ in pan for t in p["cells"].values() if TREE in t and BRUTE in t and t[TREE] > 0 and t[BRUTE] > 0]
    SCALE["neg"] = max(0.01, -min(lrs)) if lrs else 3.0
    SCALE["pos"] = max(0.01, max(lrs)) if lrs else 3.0
    tie_frac = SCALE["neg"] / (SCALE["neg"] + SCALE["pos"])
    bar_w = 480
    width = max([left + len(xs) * cell_w + 60 for _, _, _, xs, _ in pan] + [left + bar_w + 40])   # room for the tie label and the color bar
    heights = [top + len(ys) * cell_h + none_gap + 70 for _, _, _, _, ys in pan]
    total_h = sum(heights) + gap * (len(pan) - 1) + 60
    svg = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" font-family="-apple-system, Helvetica, Arial, sans-serif" font-size="12">' % (width, total_h),
           '<defs><linearGradient id="ratio" x1="0" x2="1" y1="0" y2="0">']
    for i in range(41):
        f = i / 40
        lr = -SCALE["neg"] * (1 - f / tie_frac) if f < tie_frac else SCALE["pos"] * (f - tie_frac) / (1 - tie_frac)
        svg.append('<stop offset="%.3f" stop-color="%s"/>' % (f, cell_color(lr)))
    svg.append('</linearGradient></defs>')
    svg.append('<rect width="100%%" height="100%%" fill="%s"/>' % SURFACE)
    y0 = 10
    for host, cpu, p, xs, ys in pan:
        svg.append('<text x="%d" y="%d" font-size="15" font-weight="600" fill="%s">%s</text>' % (left, y0 + 18, INK, esc("%s, %s cores" % (cpu, p["meta"]["cores"]))))
        if p["mode"] == "neighbors":
            params = "parameters: %s / %s points per side, %s redundancy, 3 ratio" % ("{:,}".format(int(p["meta"]["nA"])), "{:,}".format(int(p["meta"]["nB"])), p["meta"]["redundancy"])
        else:
            params = "parameters: %s neighbors, %s redundancy, 3 ratio" % (p["meta"]["neighbors"], p["meta"]["redundancy"])
        svg.append('<text x="%d" y="%d" fill="%s">%s</text>' % (left, y0 + 38, INK2, esc(params)))
        gy = y0 + 54
        row_y = {}
        for yi, rad in enumerate(ys):
            cy = gy + yi * cell_h + (none_gap if rad == 0 else 0)
            row_y[rad] = cy
            svg.append('<text x="%d" y="%d" text-anchor="end" fill="%s">%s</text>' % (left - 10, cy + cell_h / 2 + 4, INK, fmt_radius(rad)))
            for xi, d in enumerate(xs):
                cx = left + xi * cell_w
                times = p["cells"].get((d, rad), {})
                if TREE not in times or BRUTE not in times:
                    svg.append('<rect x="%d" y="%d" width="%d" height="%d" fill="none" stroke="%s"/>' % (cx + 1, cy + 1, cell_w - 2, cell_h - 2, GRID))
                    continue
                ratio = times[BRUTE] / times[TREE]
                lr = math.log2(ratio) if ratio > 0 else 0
                nA, nB, inr, cand, same = p["nA"][(d, rad)]
                winner = ("flat KD-tree %s faster" % fmt_ratio(ratio)) if ratio >= 1 else ("brute force %s faster" % fmt_ratio(1 / ratio))
                head = ("%d neighbors" % d) if p["mode"] == "neighbors" else ("descriptors B=%d" % d)
                tip = "%s (points A=%d, B=%d), radius %s, avg B in radius %.0f\n%s\nflat KD-tree %.0f ms, brute force %.0f ms\n%s candidates, identical sets: %s" % (
                    head, nA, nB, fmt_radius(rad), inr, winner, times[TREE], times[BRUTE], cand, same)
                svg.append('<g><title>%s</title><rect x="%d" y="%d" width="%d" height="%d" rx="4" fill="%s"/></g>' % (esc(tip), cx + 1, cy + 1, cell_w - 2, cell_h - 2, cell_color(lr)))
        # tie boundary: interpolate log2(ratio) = 0 between consecutive radius rows (radius > 0 only) per column
        pts = []
        radii = [r for r in ys if r > 0]
        for xi, d in enumerate(xs):
            lrs = []
            for rad in radii:
                t = p["cells"].get((d, rad), {})
                lrs.append(math.log2(t[BRUTE] / t[TREE]) if TREE in t and BRUTE in t else None)
            cx = left + xi * cell_w + cell_w / 2
            found = False
            for i in range(len(radii) - 1):
                a, b = lrs[i], lrs[i + 1]
                if a is None or b is None or a == 0 or (a > 0) == (b > 0):
                    continue
                f = a / (a - b)
                ya, yb = row_y[radii[i]] + cell_h / 2, row_y[radii[i + 1]] + cell_h / 2
                pts.append((cx, ya + f * (yb - ya)))
                found = True
                break
            if not found and lrs and all(l is not None for l in lrs):
                # no sign change: the boundary lies outside the sampled radii for this column
                pts.append((cx, row_y[radii[-1]] + cell_h if lrs[-1] < 0 else row_y[radii[0]]))
        if len(pts) >= 2:
            first, last = pts[0], pts[-1]
            ext = [(left, first[1])] + pts + [(left + len(xs) * cell_w, last[1])]
            svg.append('<polyline points="%s" fill="none" stroke="%s" stroke-width="2.5" stroke-linejoin="round" stroke-dasharray="7 4"/>' % (
                " ".join("%.1f,%.1f" % q for q in ext), INK))
            svg.append('<text x="%d" y="%.1f" fill="%s" font-size="11" font-weight="600">tie</text>' % (left + len(xs) * cell_w + 6, last[1] + 4, INK))
        bottom = gy + len(ys) * cell_h + (none_gap if 0 in ys else 0)
        for xi, d in enumerate(xs):
            svg.append('<text x="%d" y="%d" text-anchor="middle" fill="%s">%s</text>' % (left + xi * cell_w + cell_w / 2, bottom + 18, INK, "{:,}".format(d)))
        xlabel = "number of neighbors" if p["mode"] == "neighbors" else "number of descriptors per side"
        svg.append('<text x="%d" y="%d" text-anchor="middle" fill="%s">%s</text>' % (left + len(xs) * cell_w / 2, bottom + 38, INK2, xlabel))
        svg.append('<text transform="translate(%d,%d) rotate(-90)" text-anchor="middle" fill="%s">search radius (world units)</text>' % (24, gy + len(ys) * cell_h / 2, INK2))
        y0 = bottom + 50 + gap
    # continuous color bar
    lx, ly, lw = left, total_h - 60, bar_w
    svg.append('<rect x="%d" y="%d" width="%d" height="14" rx="3" fill="url(#ratio)"/>' % (lx, ly, lw))
    def bar_x(lr):
        return lx + (tie_frac * (1 - (-lr) / SCALE["neg"]) if lr < 0 else tie_frac + (1 - tie_frac) * lr / SCALE["pos"]) * lw
    # few ticks: the tie, each end, and 10x on a side when it sits clear of the end
    ticks = [(0, "tie"), (-SCALE["neg"], fmt_ratio(2 ** SCALE["neg"])), (SCALE["pos"], fmt_ratio(2 ** SCALE["pos"]))]
    ten = math.log2(10)
    if SCALE["neg"] > ten * 1.6: ticks.append((-ten, "10×"))
    if SCALE["pos"] > ten * 1.6: ticks.append((ten, "10×"))
    for lr, lab in ticks:
        x = bar_x(lr)
        svg.append('<line x1="%.1f" x2="%.1f" y1="%d" y2="%d" stroke="%s"/>' % (x, x, ly + 14, ly + 19, INK2))
        svg.append('<text x="%.1f" y="%d" text-anchor="middle" fill="%s" font-size="11">%s</text>' % (x, ly + 32, INK2, lab))
    svg.append('<text x="%d" y="%d" fill="%s" font-size="11">← brute force faster</text>' % (lx, ly - 8, INK2))
    svg.append('<text x="%d" y="%d" text-anchor="end" fill="%s" font-size="11">flat KD-tree faster →</text>' % (lx + lw, ly - 8, INK2))
    svg.append("</svg>")
    with open(out, "w") as f:
        f.write("\n".join(svg))
    print("wrote", out)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tsv", nargs="+")
    ap.add_argument("-o", "--out", default="rgldm-decision.svg")
    ap.add_argument("--x", choices=["auto", "descriptors", "neighbors"], default="auto", help="x-axis; auto picks neighbors when the TSV sweeps them")
    a = ap.parse_args()
    rows = load(a.tsv)
    render(panels(rows, x_mode(rows, a.x)), a.out)
