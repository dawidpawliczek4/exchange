# exchange

A small trading-exchange matching engine, built as a learning project.

I wanted to understand two things:

1. How an exchange actually works
2. How distributed systems work

---

## Why hexagonal

So I can plug in different implementations of the same port and benchmark them side by side.

Some claude's visualization of architecture:

```
                 driving (inbound)                      driven (outbound)
                        │                                       │
   HTTP  ──►  OrderController ──►  OrderService  ──►  CommandLog (port)
   JMH   ──►  (benchmark)         (core, pure)              │
                                       │            ┌───────┴────────┐
                                  OrderBook      FileCommandLog   InMemoryCommandLog
                                  (domain)       (fsync WAL)      (zero I/O)
```

### Modules

| Module | Language             | Role                       |
|---|----------------------|----------------------------|
| `:engine` | pure Java            | The core.                  |
| `:app` | Kotlin + Spring Boot | Driving & driven adapters. |
| `:benchmark` | Java + JMH           | Measures the engine.       |

Keeping the engine framework-free is deliberate: the core stays deterministic and trivially testable,
and the heavy machinery (Spring, I/O) lives only in adapters that can be replaced.

---

## Running it

Requires JDK 25 (the Gradle toolchain will fetch it if missing).

```bash
# Start the REST API
./gradlew :app:bootRun

# POST an order:
curl -X POST localhost:8080/order -H 'Content-Type: application/json' \
  -d '{"userId":1,"side":"BUY","price":100,"market":false,"quantity":5}'

# Run all tests
./gradlew test

# Run the benchmarks (JMH)
./gradlew :benchmark:jmh
```

### Configuration


- You can choose which `CommandLog` implementation the app uses via `application.yml`:

```yaml
exchange:
  commandlog: file   # "file" (durable, fsync WAL) or "memory" (zero I/O, lost on exit)
```

---
## Tech stack

Java 25 · Kotlin · Spring Boot 4 · Gradle (multi-module, version catalog, convention plugins) ·
JMH · JUnit 5.
