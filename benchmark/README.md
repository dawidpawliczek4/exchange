# :benchmark

JMH harnesses for the engine: `OrderBookBenchmark` (closed-loop throughput of the pure
book) and `LatencyBenchmark` (open-loop latency distribution, HdrHistogram).

What they measure, the results so far, and how much to trust them:
[docs/benchmarking.md](../docs/benchmarking.md). Analysis-pipeline usage (CSV, plots):
[analysis/README.md](analysis/README.md).

Note: a bare `./gradlew :benchmark:jmh` runs **both** harnesses — `LatencyBenchmark`'s
`@Fork(10)` adds ~half an hour. `results/` and `figures/` are committed archives; add new
dated runs, never overwrite.
