#!/usr/bin/env python3
"""Merge open-loop latency histograms and render the percentile figure.

    python3 plot_latency.py ../results/2026-08-27_latency-mac -o ../figures

Input: HdrHistogram interval logs written by LatencyBenchmark, one file per
measured iteration, named latency-p<periodNs>-f<pid>-i<NN>.hlog. All files
sharing a periodNs are merged into a single histogram (histogram addition is
exact, unlike averaging percentile tables), so the plotted curve represents
the full distribution across forks and iterations.

Produces, in SVG, PDF and PNG:
  latency-percentiles      latency vs percentile (HdrHistogram-style axis,
                           straight x-axis in log 1/(1-p)), one curve per
                           arrival rate — the plot where the tail lives
"""

import argparse
import pathlib
import re
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from hdrh.histogram import HdrHistogram
from hdrh.log import HistogramLogReader

INK = "#0b0b0b"
INK_SOFT = "#52514e"
INK_MUTED = "#898781"
GRID = "#e1e0d9"
SERIES = ["#2a78d6", "#d6702a", "#3d9c4f", "#a04bd6", "#c23b3b"]

FILE_RE = re.compile(r"latency-p(\d+)-f\d+-i\d+\.hlog$")

PERCENTILE_TICKS = [0.0, 90.0, 99.0, 99.9, 99.99, 99.999]


def load_merged(results_dir):
    merged = {}
    files = sorted(results_dir.glob("latency-p*-f*-i*.hlog"))
    if not files:
        sys.exit(f"no latency-p*.hlog files in {results_dir}")
    for path in files:
        m = FILE_RE.search(path.name)
        if not m:
            continue
        period_ns = int(m.group(1))
        target = merged.setdefault(period_ns, HdrHistogram(1, 3600_000_000_000, 3))
        reader = HistogramLogReader(str(path), HdrHistogram(1, 3600_000_000_000, 3))
        n = 0
        while True:
            interval = reader.get_next_interval_histogram()
            if interval is None:
                break
            target.add(interval)
            n += 1
        if n == 0:
            sys.exit(f"{path}: no interval histogram decoded — file truncated?")
    return merged


def rate_label(period_ns):
    rate = 1e9 / period_ns
    if rate >= 1000:
        return f"{rate / 1000:g}k zleceń/s (co {period_ns / 1000:g} µs)"
    return f"{rate:g} zleceń/s (co {period_ns / 1e6:g} ms)"


def percentile_curve(hist):
    xs, ys = [], []
    k = 0.0
    while k <= 5.0:
        p = 100.0 * (1.0 - 10.0**-k)
        xs.append(10.0**k)
        ys.append(hist.get_value_at_percentile(p) / 1000.0)
        k += 0.05
    return xs, ys


def style_axes(ax):
    ax.grid(True, color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)
    for spine in ("left", "bottom"):
        ax.spines[spine].set_color(INK_MUTED)
    ax.tick_params(colors=INK_SOFT, labelsize=9)


def render(merged, out_dir):
    fig, ax = plt.subplots(figsize=(7.2, 4.6))
    for color, period_ns in zip(SERIES, sorted(merged, reverse=True)):
        hist = merged[period_ns]
        xs, ys = percentile_curve(hist)
        ax.plot(xs, ys, color=color, linewidth=1.8, label=rate_label(period_ns))

    ax.set_xscale("log")
    ax.set_yscale("log")
    ticks = [1.0 / (1.0 - p / 100.0) if p else 1.0 for p in PERCENTILE_TICKS]
    ax.set_xticks(ticks)
    ax.set_xticklabels(["0%", "90%", "99%", "99,9%", "99,99%", "99,999%"])
    ax.set_xlim(1, 10**5)

    style_axes(ax)
    ax.set_xlabel("percentyl", color=INK_SOFT, fontsize=10)
    ax.set_ylabel("opóźnienie [µs]", color=INK_SOFT, fontsize=10)
    ax.set_title(
        "Opóźnienie od zamierzonego startu do potwierdzenia (pętla otwarta)",
        color=INK,
        fontsize=11,
        pad=12,
    )
    ax.legend(frameon=False, fontsize=9, labelcolor=INK_SOFT)
    fig.tight_layout()

    out_dir.mkdir(parents=True, exist_ok=True)
    for ext in ("svg", "pdf", "png"):
        fig.savefig(out_dir / f"latency-percentiles.{ext}", dpi=200)
    plt.close(fig)


def print_summary(merged):
    print(f"{'okres':>12} {'próbki':>10} {'p50':>9} {'p99':>9} {'p99,9':>9} {'max':>9}  [µs]")
    for period_ns in sorted(merged, reverse=True):
        hist = merged[period_ns]
        row = [hist.get_value_at_percentile(p) / 1000.0 for p in (50, 99, 99.9)]
        print(
            f"{period_ns / 1000:>10g}µs {hist.get_total_count():>10} "
            f"{row[0]:>9.1f} {row[1]:>9.1f} {row[2]:>9.1f} {hist.get_max_value() / 1000.0:>9.1f}"
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results_dir", type=pathlib.Path)
    parser.add_argument("-o", "--out", type=pathlib.Path, default=pathlib.Path("../figures"))
    args = parser.parse_args()

    merged = load_merged(args.results_dir)
    print_summary(merged)
    render(merged, args.out)
    print(f"\nwritten: {args.out}/latency-percentiles.{{svg,pdf,png}}")


if __name__ == "__main__":
    main()
