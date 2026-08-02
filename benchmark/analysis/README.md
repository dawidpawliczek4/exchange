# Benchmark analysis pipeline

Raw JMH output lives under `benchmark/build/` and is destroyed by `gradlew clean`.
Anything a thesis chapter will cite has to be lifted out of there, normalised, and
committed. That is what this directory does.

```
gradlew :benchmark:jmh          →  build/results/jmh/results.json   (raw, throwaway)
jmh_to_csv.py                   →  ../results/<date>_<sweep>.csv    (archival, committed)
plot_spread.py                  →  ../figures/*.svg + *.pdf         (vector, for LaTeX)
```

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
- **`Wyklad1` runs too** and lands in the same result file. To measure only the
  order book, add `includes = listOf("OrderBookBenchmark")` to the `jmh {}` block in
  `benchmark/build.gradle.kts`.
- **A laptop is not a measurement environment.** Every number collected so far came
  off an M4 Pro laptop with thermal throttling and background load uncontrolled.
  That is fine for exploring; it is not fine for the thesis. Re-collect final numbers
  on a quiet machine with fixed clocks and say so in the methodology chapter.
- **`@Fork(1)` makes JMH's reported error meaningless** — it measures only
  within-JVM iteration spread and excludes the JVM-to-JVM variance, which is the
  larger term. Measured directly: at one fork two identical runs differed by 3.4%
  while reporting ±1.7%, and the error estimate itself moved by 4×. Ten forks fixed
  it (≤1.5% relative, reproducible). Do not lower it.
