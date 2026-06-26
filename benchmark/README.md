# :benchmark

JMH benchmarks for the exchange.

| Benchmark | Scope | Stack |
|---|---|---|
| `OrderBookBenchmark` | **unit / L0** | pure `OrderBook`, no Spring, no I/O |

A command-log / WAL benchmark (driving `OrderService` directly) is a TODO.

## Run

```bash
./gradlew :benchmark:jmh                                        # everything
```

Filter / tune via the `jmh { }` block in `build.gradle.kts` (`includes`, `warmupIterations`,
`iterations`, `fork`).
