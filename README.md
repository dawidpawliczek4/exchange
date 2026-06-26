# exchange

A small trading-exchange matching engine, built as a learning project.

I wanted to understand two things:

1. How an exchange actually works
2. How distributed systems work

---

### Modules

| Module | Language             | Role                              |
|---|----------------------|-----------------------------------|
| `:contracts` |  Java            | The wire: shared types + codec.   |
| `:engine` |  Java            | The core.                         |
| `:matching-service` | Kotlin               | Runs the engine off Kafka.        |
| `:app` | Kotlin + Spring Boot | Gateway: REST in, trades out.     |
| `:benchmark` | Java + JMH           | Measures the engine.              |

Orders flow `gateway → orders.commands → matching-service → orders.trades → gateway`. The gateway
validates and publishes; the matching service consumes commands, matches them in the engine, and
publishes the trades back; the gateway fans those out over WebSocket.

---

## Running it

Brings up Kafka, the matching service, and the gateway (requires Docker):

```bash
docker compose -f devops/docker-compose.yml up -d --build
```

Then drive it:

```bash
# Watch trades over WebSocket
websocat ws://localhost:8080/marketdata

# POST two crossing orders
curl -X POST localhost:8080/order -H 'Content-Type: application/json' \
  -d '{"userId":1,"side":"BUY","price":100,"market":false,"quantity":5}'
curl -X POST localhost:8080/order -H 'Content-Type: application/json' \
  -d '{"userId":2,"side":"SELL","price":100,"market":false,"quantity":5}'
  
# → the trade shows up in the websocat

# Shut down
docker compose -f devops/docker-compose.yml down
```

### Tests & benchmarks

Requires JDK 25 (the Gradle toolchain fetches it if missing).

```bash
./gradlew test            # all module tests
./gradlew :benchmark:jmh  # JMH benchmarks
```

### Local development

To iterate on a service without rebuilding its image, bring up just Kafka and run the service from
Gradle (it defaults to `localhost:9092`):

```bash
docker compose -f devops/docker-compose.yml up -d kafka
./gradlew :matching-service:run
./gradlew :app:bootRun
```

### Configuration

The matching service persists every order to a write-ahead log (`journal.bin`) and rebuilds its book
from it on restart. Under Compose this lives on the `journal` volume, so it survives container
restarts.

Kafka bootstrap defaults to `localhost:9092`; Compose wires the services to the broker at
`kafka:29092`. Override per service:

- matching service — `KAFKA_BOOTSTRAP_SERVERS`
- gateway — `SPRING_KAFKA_BOOTSTRAP_SERVERS`

---
## Tech stack

Java 25 · Kotlin · Spring Boot 4 · Apache Kafka · Gradle (multi-module, version catalog, convention
plugins) · JMH · JUnit 5.
