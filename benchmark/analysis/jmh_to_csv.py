#!/usr/bin/env python3
"""Normalise a raw JMH JSON run into an archival CSV.

    python3 jmh_to_csv.py ../build/results/jmh/results.json \\
        --benchmark OrderBookBenchmark.matchBatch --param spread \\
        -o ../results/2026-07-31_spread-sweep.csv

The CSV is the artefact the thesis cites and the plotting script reads: raw JMH
output lives under build/ and is wiped by `gradlew clean`, so anything worth
keeping has to be lifted out of it. Environment metadata is written into `#`
comments so a result file is still readable in a year without this repo.
"""

import argparse
import csv
import json
import pathlib
import platform
import subprocess
import sys
from datetime import date


def sysinfo():
    info = {"os": f"{platform.system()} {platform.release()}", "machine": platform.machine()}
    try:
        if platform.system() == "Darwin":
            cpu = subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"],
                                 capture_output=True, text=True, timeout=5).stdout.strip()
            cores = subprocess.run(["sysctl", "-n", "hw.ncpu"],
                                   capture_output=True, text=True, timeout=5).stdout.strip()
            mem = subprocess.run(["sysctl", "-n", "hw.memsize"],
                                 capture_output=True, text=True, timeout=5).stdout.strip()
            info["machine"] = f"{cpu}, {cores} cores, {int(mem) // 2**30} GB RAM"
            info["os"] = f"macOS {platform.mac_ver()[0] or platform.release()}"
    except Exception:
        pass
    for key, cmd in (("git_commit", ["git", "rev-parse", "--short", "HEAD"]),
                     ("git_dirty", ["git", "status", "--porcelain"])):
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=5).stdout.strip()
            info[key] = ("true" if out else "false") if key == "git_dirty" else out
        except Exception:
            info[key] = "unknown"
    return info


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("json", type=pathlib.Path)
    ap.add_argument("--benchmark", required=True, help="substring match on the benchmark name")
    ap.add_argument("--param", required=True, help="the @Param varied in this sweep")
    ap.add_argument("-o", "--out", type=pathlib.Path, required=True)
    ap.add_argument("--note", action="append", default=[], metavar="TEXT",
                    help="extra provenance line, repeatable. JMH does not record what "
                         "the workload actually was — say it here (distribution, seed, "
                         "batch size), or the file is not self-describing.")
    args = ap.parse_args()

    runs = [r for r in json.loads(args.json.read_text()) if args.benchmark in r["benchmark"]]
    if not runs:
        sys.exit(f"no benchmark matching {args.benchmark!r} in {args.json}")
    runs = [r for r in runs if args.param in r.get("params", {})]
    if not runs:
        sys.exit(f"benchmark found but no @Param {args.param!r} — is the JSON stale?")

    unit = runs[0]["primaryMetric"]["scoreUnit"]
    if unit != "ops/s":
        sys.exit(f"expected ops/s, got {unit!r}; adjust the derived columns first")

    runs.sort(key=lambda r: float(r["params"][args.param]))
    head, sys_ = runs[0], sysinfo()
    base = runs[0]["primaryMetric"]["score"]

    rows = []
    for r in runs:
        pm = r["primaryMetric"]
        p = float(r["params"][args.param])
        samples = sum(len(s) for s in pm.get("rawData", []))
        rows.append({
            args.param: f"{p:g}",
            "price_levels": f"{int(2 * p + 1)}",
            "ops_per_s": f"{pm['score']:.3f}",
            "error_ops_per_s": f"{pm['scoreError']:.3f}",
            "rel_error_pct": f"{pm['scoreError'] / pm['score'] * 100:.3f}",
            "ns_per_op": f"{1e9 / pm['score']:.3f}",
            "rel_throughput_pct": f"{pm['score'] / base * 100:.3f}",
            "samples": samples,
        })

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", newline="", encoding="utf-8") as fh:
        for line in (
            f"benchmark: {head['benchmark']}",
            f"swept_param: {args.param}",
            f"date: {date.today().isoformat()}",
            f"git_commit: {sys_['git_commit']}",
            f"git_dirty: {sys_['git_dirty']}",
            "",
            f"jmh_mode: {head['mode']}",
            f"jdk: {head.get('jdkVersion')}, {head.get('vmName')}, {head.get('vmVersion')}",
            f"vm_options: {' '.join(head.get('jvmArgs') or []) or '(defaults)'}",
            f"forks: {head.get('forks')}",
            f"warmup: {head.get('warmupIterations')} x {head.get('warmupTime')}",
            f"measurement: {head.get('measurementIterations')} x {head.get('measurementTime')}",
            "",
            f"machine: {sys_['machine']}",
            f"os: {sys_['os']}",
            "WARNING: confirm this was a quiet machine with fixed clocks before citing.",
            "",
            *args.note,
            *([""] if args.note else []),
            "price_levels = 2*spread + 1",
            "error = half-width of the 99.9% confidence interval, as reported by JMH",
            "",
        ):
            fh.write(f"# {line}\n" if line else "#\n")
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)

    print(f"{args.out} ← {len(rows)} punktów, {rows[0]['samples']} próbek na punkt")


if __name__ == "__main__":
    main()
