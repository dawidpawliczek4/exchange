# Benchmarks — state, results, credibility

As of 2026-08-27. What has been measured in `:benchmark` so far, how much to trust the
numbers, and what follows for the thesis. Sources: code in `benchmark/src/jmh/`, data in
`benchmark/results/`, pipeline in `benchmark/analysis/` (usage:
[benchmark/analysis/README.md](../benchmark/analysis/README.md)).

**`benchmark/results/` is a committed archive.** It is the provenance trail for thesis
numbers — never delete it, and never overwrite an existing dated run; add a new dated
directory/file instead. **`benchmark/figures/` is gitignored, not archived** — the plots
are pure derivatives of `results/`, regenerated on demand by the two plot scripts (a
byte-comparison confirmed the committed figures were reproducible from the committed data,
modulo matplotlib's random element ids and embedded timestamp). `requirements.txt` pins
matplotlib exactly so that stays true over the life of the thesis.

## 1. What exists

| Benchmark | Mode | What it measures |
|---|---|---|
| `OrderBookBenchmark.matchBatch` | `Throughput` | per-operation cost of the pure `OrderBook` (no Spring, no Kafka, no I/O) as a function of book width |
| `LatencyBenchmark.run` | `SingleShotTime` (JMH as a shell) | open-loop latency distribution at a given arrival rate: `OrderService` (queue + `matching-writer` thread + in-memory WAL), section 3 |

`Workload.generate(n, spread, seed)` — deterministic generator: prices uniform in
`[10000−spread, 10000+spread]`, side by fair coin, quantity 1–10, limit orders only,
fixed seed 42.

`matchBatch` configuration: 10 forks (`-Xms2g -Xmx2g -XX:+AlwaysPreTouch`), 10 warmup +
20 measurement iterations of 1s, 50 000 orders per invocation, fresh `OrderBook` per
invocation (`Level.Invocation`), `spread ∈ {10, 100, 1000, 10000}`.

Analysis pipeline: `gradlew :benchmark:jmh` → `results.json` (ephemeral, in `build/`) →
`jmh_to_csv.py` → CSV with environment metadata (commit, JDK, flags, machine) in
`benchmark/results/` (committed) → `plot_spread.py` → SVG/PDF figures in
`benchmark/figures/` (regenerated on demand, not committed).

## 2. Results: degradation with book width

Run `2026-07-31_spread-sweep.csv` (Apple M4 Pro, macOS, JDK 25, JMH 1.37):

| spread | price levels | ops/s | ns/op | vs spread=10 |
|---|---|---|---|---|
| 10 | 21 | 20,598,935 ± 1.03% | 48.5 | 100% |
| 100 | 201 | 15,953,861 ± 1.53% | 62.7 | 77% |
| 1000 | 2,001 | 12,053,757 ± 0.38% | 83.0 | 59% |
| 10000 | 20,001 | 9,353,060 ± 0.51% | 106.9 | 45% |

Interpretation:

- **A 1000× wider book costs only 2.2×** — the signature of logarithmic complexity,
  consistent with the `TreeMap` underneath. Good news and a good number for the
  implementation chapter.
- **But the cost per doubling of levels grows** (4.3 → 6.1 → 7.2 ns/op per doubling): the
  curve is convex on a log scale, so pure log isn't the whole story. Three mechanisms
  move together and this experiment does **not** separate them: (a) cache hierarchy — a
  wider book is a bigger tree with worse locality; (b) GC pressure — with a wide book
  almost nothing matches, so `Level.Invocation` discards ~50k live orders per invocation;
  (c) shifting code-path mix — the wider the book, the fewer matches and the more
  insertions. Separating them: `-prof gc` + a counter of actual matches (in the backlog).

## 3. Open loop: latency as a function of arrival rate

`LatencyBenchmark` is the thesis plan's measurement apparatus in its first working
incarnation. Construction:

- **Open loop with intended start times**: the generator fires on the schedule
  `t0 + i·period` (spin-wait), regardless of whether the engine keeps up. Latency is
  measured from the *intended* start to future completion — robust to coordinated
  omission (Tene): a momentary engine hiccup charges the percentiles of every backlogged
  order, exactly as a client would see it.
- **JMH purely as a lifecycle shell** (`SingleShotTime`): warmup, forks and `@Param` for
  free; the JMH result (s/op) is ignored, the measurement lives in its own
  `ConcurrentHistogram` (auto-resize; recorded on the `matching-writer` thread in
  `whenComplete`). Each iteration runs a fixed 5s — the order count follows from the
  period.
- **Fresh `OrderService` + `InMemoryCommandLog` per iteration** — the book does not
  accumulate depth across iterations; the WAL is in memory, so what's measured is the
  engine with its queue, *without* fsync cost (persistence strategies are a separate,
  future run).
- **Output**: one HdrHistogram interval log per measured iteration
  (`results/<date>_<run>/latency-p<period>-f<pid>-i<NN>.hlog`), merged exactly
  (histogram `add()`, not averaging of percentile tables) by `plot_latency.py` into a
  latency-vs-percentile figure in `figures/` (regenerated on demand, not committed).

Run `2026-08-27_latency-mac` (M4 Pro, 3 forks × 5 iterations, exploratory):

| arrival rate | samples | p50 | p99 | p99.9 | max |
|---|---|---|---|---|---|
| 100/s | 7.5k | 39.5 µs | 87 µs | 949 µs | 3.2 ms |
| 1k/s | 75k | 9.8 µs | 37 µs | 1.17 ms | 9.9 ms |
| 10k/s | 750k | 7.5 µs | 27 µs | 1.12 ms | 14.5 ms |
| 100k/s | 7.5M | 6.3 µs | 34 µs | 8.1 ms | 14.5 ms |

Two observations, both thesis-ready:

1. **Median falls with load** (39.5 → 6.3 µs). At a sparse stream every order pays the
   full park/unpark of the `matching-writer` thread (~30–40 µs); at a dense one the
   thread stays hot and `drainTo` amortises the handoff over the whole batch. The
   single-writer's batching, visible directly in the data.
2. **At 100k/s the tail breaks around p99** and jumps to ~8 ms: a hiccup (GC/scheduler)
   builds a queue backlog that subsequent orders inherit. A closed loop would not show
   this — it is exactly the artefact the apparatus is open for. Lower rates have similar
   maxima but rarer, hence the divergence only past p99.

Credibility: same caveats as section 4 (laptop, throttling, background load) plus the
in-memory WAL — the numbers are exploratory; the final campaign runs with `@Fork(10)` on
a desktop Linux box after code freeze.

## 4. Are these numbers credible

**Yes — as exploratory results. No — as thesis numbers.** Concretely:

What is solid:

- **10 forks.** Measured directly: at `@Fork(1)` two identical runs differed by 3.4%
  while reporting ±1.7%, and the error estimate itself moved by 4×. Ten forks brought
  the error to ≤1.5% relative and stabilised it. Do not lower it.
- Deterministic workload (seed 42), fixed heap with `AlwaysPreTouch`, warmup before
  measurement, 200 samples per point.
- The CSV carries full environment metadata — a result stays interpretable without this
  repo.

What undermines the numbers (all known and recorded, nothing hidden):

1. **Laptop.** Run-to-run repeatability ~10% despite ~1% confidence intervals: the same
   configuration at spread=10 gave 22.86M ops/s in a short run and 20.60M in a 20-minute
   sweep — thermal throttling. JMH's confidence interval describes precision *within* a
   run, not repeatability *between* runs. **Final thesis numbers get collected on a
   desktop Linux machine with fixed clocks.**
2. **Provenance of the July 31 run**: the CSV declares commit `ead71a4` with
   `git_clean: false` (the spread parameterisation wasn't committed yet), so exactly
   reproducing *that* run from git history is impossible. The current repo state has the
   right code — the next run will be clean.
3. **The workload shape is unrealistic**: uniform price distribution, while real books
   have power-law tails (Bouchaud et al. 2002). The qualitative conclusion (~log
   degradation) will hold; the specific numbers may not. Gaussian generator in the
   backlog, LOBSTER validation planned.

## 5. Methodological findings (thesis material in their own right)

1. **`@Fork(1)` makes the reported error a fiction** — it measures only within-JVM
   spread and excludes JVM-to-JVM variance, which is the larger term.
2. **Confidence interval ≠ repeatability** — on the laptop the between-run difference
   (~10%) was an order of magnitude larger than the declared error (~1%). A
   repeatability test (two independent full runs) is mandatory before citing numbers.

Both findings are measured, not hearsay — ready-made paragraphs for the methodology
chapter as justification of the apparatus design.

Operational traps (already paid for — see also the gotchas in
[benchmark/analysis/README.md](../benchmark/analysis/README.md)):

- `build/results/jmh/results.json` **is not cleaned between runs** — a stale file from a
  deleted benchmark looks perfectly valid. Check the date.
- `LatencyBenchmark` **runs on every bare `:benchmark:jmh`** — with its default
  `@Fork(10)` that appends ~half an hour to a book sweep. Run the latency sweep from the
  jar (`java -jar benchmark-jmh.jar LatencyBenchmark`, see the analysis README); to
  measure only the book, add `includes = listOf("OrderBookBenchmark")` to the `jmh {}`
  block in `benchmark/build.gradle.kts`.

## 6. What these benchmarks do NOT measure

`matchBatch` is a closed loop on the pure book: **operation cost**, not latency. The
latency distribution at a given arrival rate is `LatencyBenchmark` (section 3) — but so
far with an in-memory WAL, i.e. without the cost of durability. Division of labour:

| Apparatus | Question | Level |
|---|---|---|
| `OrderBookBenchmark` | what does a book operation cost | micro, no I/O |
| `LatencyBenchmark` | latency distribution at a given λ | engine + queue, WAL in memory |
| (future run with `FileCommandLog`) | what does the persistence strategy add | system, WAL on disk |

## 7. Measurement backlog

- [ ] `includes = listOf("OrderBookBenchmark")` in `jmh {}`
- [ ] `-prof gc` + match counter — decompose the convexity in section 2
- [ ] Separate `Mode.SampleTime` benchmark (micro-level percentiles)
- [ ] Gaussian generator (sigma as `@Param`), generation in `@Setup(Level.Trial)`, fixed seed
- [ ] Pre-filling the book to a given depth in `@Setup`
- [ ] `LatencyBenchmark` with `FileCommandLog` — group fsync vs per-record vs none (measurement 3 of the thesis plan)
- [ ] Re-run the whole sweep on the measurement machine after code freeze
