# exchange

A small trading-exchange matching engine, built as a learning project.

I wanted to understand two things:

1. How an exchange actually works
2. How distributed systems work

---

### Modules

| Module | Language             | Role                              |
|---|----------------------|-----------------------------------|
| `:contracts` | pure Java            | The wire: shared types + codec.   |
| `:engine` | pure Java            | The core.                         |
| `:matching-service` | Kotlin               | Runs the engine off Kafka.        |
| `:app` | Kotlin + Spring Boot | Gateway: REST in, trades out.     |
| `:benchmark` | Java + JMH           | Measures the engine.              |

Orders flow `gateway → orders.commands → matching-service → orders.trades → gateway`. The gateway
validates and publishes; the matching service consumes commands, matches them in the engine, and
publishes the trades back; the gateway fans those out over WebSocket.

---

## Running it

Requires JDK 25 (the Gradle toolchain will fetch it if missing) and a Kafka broker.

```bash
# Start Kafka
docker compose -f devops/docker-compose.yml up -d

# Run the matching service (engine + Kafka consumer/producer)
./gradlew :matching-service:run

# Run the gateway (REST + WebSocket), in another terminal
./gradlew :app:bootRun

# Watch trades over WebSocket (subscribe first, in another terminal)
websocat ws://localhost:8080/marketdata

# Then POST two crossing orders — each returns 202, the gateway is fire-and-forget
curl -X POST localhost:8080/order -H 'Content-Type: application/json' \
  -d '{"userId":1,"side":"BUY","price":100,"market":false,"quantity":5}'
curl -X POST localhost:8080/order -H 'Content-Type: application/json' \
  -d '{"userId":2,"side":"SELL","price":100,"market":false,"quantity":5}'
# → the trade shows up in the websocat terminal
```

```bash
# Run all tests
./gradlew test

# Run the benchmarks (JMH)
./gradlew :benchmark:jmh
```

### Configuration

The matching service persists every order to a write-ahead log (`journal.bin`) and rebuilds its book
from it on restart.

Kafka bootstrap defaults to `localhost:9092`. Override per service:

- matching service — `KAFKA_BOOTSTRAP_SERVERS`
- gateway — `SPRING_KAFKA_BOOTSTRAP_SERVERS`

---
## Tech stack

Java 25 · Kotlin · Spring Boot 4 · Apache Kafka · Gradle (multi-module, version catalog, convention
plugins) · JMH · JUnit 5.
