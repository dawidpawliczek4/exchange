# Benchmark analysis pipeline

Raw JMH output lives under `benchmark/build/` and is destroyed by `gradlew clean`.
Anything a thesis chapter will cite has to be lifted out of there, normalised, and
committed. That is what this directory does.

```
gradlew :benchmark:jmh          →  build/results/jmh/results.json   (raw, throwaway)
jmh_to_csv.py                   →  ../results/<date>_<sweep>.csv    (archival, committed)
plot_spread.py                  →  ../figures/*.svg + *.pdf         (derived, gitignored)
```

Only the middle row is committed. `../figures/` is gitignored: the plots are a pure
function of `../results/` plus these scripts, so they are regenerated on demand rather
than archived. That holds only as long as the toolchain is fixed — hence the exact pins
in `requirements.txt`; loosen them and the thesis figures stop being reproducible.

The open-loop latency benchmark writes its own archival files directly
(`LatencyBenchmark` → `../results/<date>_<run>/latency-p*.hlog`, one HdrHistogram
interval log per measured iteration; `results.dir` system property picks the dir):

```bash
java -Dresults.dir=benchmark/results/$(date +%F)_latency-<machine> \
    -jar benchmark/build/libs/benchmark-jmh.jar LatencyBenchmark

.venv/bin/python plot_latency.py ../results/<date>_latency-<machine> -o ../figures
```

`plot_latency.py` merges the logs per arrival rate (exact histogram addition
across forks and iterations, not averaging of percentile tables) and renders the
latency-vs-percentile figure. Annotation defaults (`@Fork(10)`) are sized for the
final campaign; for exploration override from the CLI: `-f 3 -wi 3 -i 5`.

The CSV in the middle is the point. It carries the numbers *and* the environment
they came from (`#` comments: JDK, VM flags, fork count, machine, git commit), so a
result stays interpretable without this repo, and a reviewer can tell two runs apart.

## Setup

```bash
cd benchmark/analysis
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
```

## Run

```bash
cd /path/to/exchange
./gradlew :benchmark:jmh

cd benchmark/analysis
.venv/bin/python jmh_to_csv.py ../build/results/jmh/results.json \
    --benchmark OrderBookBenchmark.matchBatch --param spread \
    -o ../results/$(date +%F)_spread-sweep.csv

.venv/bin/python plot_spread.py ../results/$(date +%F)_spread-sweep.csv -o ../figures
```

## Gotchas paid for already

- **Check the JSON is from the run you think it is.** `build/results/jmh/` is not
  cleaned between runs, so a stale `results.json` from a deleted benchmark will sit
  there looking perfectly valid. `jmh_to_csv.py` fails loudly if the requested
  `@Param` is missing, which catches the common case — but not a stale file that
  happens to have the right shape. Check the date.
- **`LatencyBenchmark` runs too** on a bare `gradlew :benchmark:jmh` — with its
  `@Fork(10)` defaults that appends ~half an hour. Run the latency sweep from the
  jar as shown above; to measure only the order book, add
  `includes = listOf("OrderBookBenchmark")` to the `jmh {}` block in
  `benchmark/build.gradle.kts`.

Methodology-level caveats (laptop vs measurement machine, why 10 forks, confidence
interval vs repeatability) live in [docs/benchmarking.md](../../docs/benchmarking.md).
