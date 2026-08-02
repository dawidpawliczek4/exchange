#!/usr/bin/env python3
"""Render the spread-sweep figures from an archived benchmark CSV.

    python3 plot_spread.py ../results/2026-07-31_spread-sweep.csv -o ../figures

Produces, in both SVG and PDF (vector, ready for LaTeX):
  throughput-vs-spread   what the reader asks first: how much throughput is lost
  cost-vs-levels         ns/op against price levels — reveals whether the curve
                         is a straight line in log space (pure O(log n)) or bends
"""

import argparse
import csv
import pathlib
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter

SERIES = "#2a78d6"
INK = "#0b0b0b"
INK_SOFT = "#52514e"
INK_MUTED = "#898781"
GRID = "#e1e0d9"


def read_csv(path):
    meta, rows = {}, []
    with open(path, newline="", encoding="utf-8") as fh:
        body = []
        for line in fh:
            if line.startswith("#"):
                bare = line[1:].strip()
                if ":" in bare:
                    k, v = bare.split(":", 1)
                    meta.setdefault(k.strip(), v.strip())
            elif line.strip():
                body.append(line)
        for row in csv.DictReader(body):
            rows.append({k: float(v) for k, v in row.items()})
    if not rows:
        sys.exit(f"no data rows in {path}")
    return meta, sorted(rows, key=lambda r: r["spread"])


def style(ax):
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(GRID)
        ax.spines[side].set_linewidth(0.8)
    ax.yaxis.grid(True, color=GRID, linewidth=0.8, linestyle="-")
    ax.xaxis.grid(False)
    ax.set_axisbelow(True)
    ax.tick_params(colors=INK_MUTED, labelsize=9, length=0)


def caption(fig, meta):
    bits = [meta.get("machine", ""), f"JDK {meta.get('jdk', '').split(',')[0]}",
            f"{meta.get('forks', '?')} forks", meta.get("mode", "")]
    fig.text(0.0, -0.02, " · ".join(b for b in bits if b),
             fontsize=7.5, color=INK_MUTED, ha="left", va="top")


def save(fig, outdir, name):
    outdir.mkdir(parents=True, exist_ok=True)
    for ext in ("svg", "pdf"):
        fig.savefig(outdir / f"{name}.{ext}", bbox_inches="tight",
                    transparent=True, metadata=None if ext == "svg" else {})
    plt.close(fig)
    print(f"  {outdir / name}.svg / .pdf")


def fig_throughput(rows, meta, outdir):
    x = [r["spread"] for r in rows]
    y = [r["ops_per_s"] / 1e6 for r in rows]
    e = [r["error_ops_per_s"] / 1e6 for r in rows]

    fig, ax = plt.subplots(figsize=(6.4, 3.9))
    style(ax)
    ax.errorbar(x, y, yerr=e, color=SERIES, linewidth=2, marker="o",
                markersize=6, markeredgecolor="white", markeredgewidth=1.5,
                capsize=4, elinewidth=1.5, zorder=3)

    ax.set_xscale("log")
    ax.set_xticks(x)
    ax.set_xticklabels([f"{int(v)}" for v in x])
    ax.set_ylim(0, max(y) * 1.18)
    ax.yaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:.0f} mln" if v else "0"))

    ax.set_xlabel("spread — połowa szerokości arkusza w krokach notowania",
                  fontsize=9, color=INK_SOFT, labelpad=8)
    ax.set_ylabel("przepustowość [operacji/s]", fontsize=9, color=INK_SOFT, labelpad=8)
    ax.set_title("Przepustowość arkusza zleceń maleje z jego szerokością",
                 fontsize=11, color=INK, loc="left", pad=12)

    drop = (1 - rows[-1]["ops_per_s"] / rows[0]["ops_per_s"]) * 100
    ax.annotate(f"{y[0]:.1f} mln".replace(".", ","), (x[0], y[0]),
                textcoords="offset points", xytext=(8, 10),
                fontsize=9, color=INK, fontweight="bold")
    ax.annotate(f"{y[-1]:.1f} mln  (−{drop:.0f}%)".replace(".", ","), (x[-1], y[-1]),
                textcoords="offset points", xytext=(0, -16),
                fontsize=9, color=INK, fontweight="bold", ha="right", va="top")

    caption(fig, meta)
    save(fig, outdir, "throughput-vs-spread")


def fig_cost(rows, meta, outdir):
    x = [r["price_levels"] for r in rows]
    y = [r["ns_per_op"] for r in rows]

    fig, ax = plt.subplots(figsize=(6.4, 3.9))
    style(ax)

    # Straight reference line through the endpoints: what pure O(log n) would look like.
    ax.plot([x[0], x[-1]], [y[0], y[-1]], color=INK_MUTED, linewidth=1,
            linestyle=(0, (4, 3)), zorder=2)
    ax.plot(x, y, color=SERIES, linewidth=2, marker="o", markersize=6,
            markeredgecolor="white", markeredgewidth=1.5, zorder=3)

    ax.set_xscale("log")
    ax.set_xticks(x)
    ax.set_xticklabels([f"{int(v):,}".replace(",", " ") for v in x])
    ax.set_ylim(0, max(y) * 1.18)

    ax.set_xlabel("liczba poziomów cenowych w arkuszu (skala logarytmiczna)",
                  fontsize=9, color=INK_SOFT, labelpad=8)
    ax.set_ylabel("koszt operacji [ns]", fontsize=9, color=INK_SOFT, labelpad=8)
    ax.set_title("Koszt operacji rośnie szybciej niż logarytmicznie",
                 fontsize=11, color=INK, loc="left", pad=12)
    ax.annotate("odniesienie: czysta zależność logarytmiczna",
                (x[1], (y[0] + (y[-1] - y[0]) * 0.33)), textcoords="offset points",
                xytext=(10, -22), fontsize=8, color=INK_MUTED)

    caption(fig, meta)
    save(fig, outdir, "cost-vs-levels")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv", type=pathlib.Path)
    ap.add_argument("-o", "--outdir", type=pathlib.Path, default=pathlib.Path("figures"))
    args = ap.parse_args()

    meta, rows = read_csv(args.csv)
    print(f"{args.csv} → {len(rows)} punktów")
    fig_throughput(rows, meta, args.outdir)
    fig_cost(rows, meta, args.outdir)

    first, last = rows[0], rows[-1]
    print(f"\nspread {first['spread']:.0f} → {last['spread']:.0f}: "
          f"przepustowość {last['rel_throughput_pct']:.1f}% wartości wyjściowej, "
          f"koszt operacji ×{last['ns_per_op'] / first['ns_per_op']:.2f}")


if __name__ == "__main__":
    main()
