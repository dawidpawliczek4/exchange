# :benchmark

JMH benchmarks for the exchange, at two altitudes:

| Benchmark | Scope | Stack |
|---|---|---|
| `OrderBookBenchmark` | **unit / L0** | pure `OrderBook`, no Spring, no I/O |
| `integration.CommandLogBenchmark` | **integration** | `OrderController.postOrder` → `OrderService` → `OrderBook` + `CommandLog`, inside a real Spring context |

## Run

```bash
./gradlew :benchmark:jmh                                        # everything
```

Filter / tune via the `jmh { }` block in `build.gradle.kts` (`includes`, `warmupIterations`,
`iterations`, `fork`).