# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

A trading-exchange matching engine built as a learning project, split into Kafka-connected services. Gradle multi-module build (version catalog + `buildSrc` convention plugins), JDK 25, Java engine + Kotlin/Spring Boot services.

## Commands

```bash
./gradlew build                        # compile + test + spotlessCheck (what CI runs)
./gradlew test                         # all module tests
./gradlew :engine:test                 # one module's tests
./gradlew :engine:test --tests "*OrderBookTest"   # a single test class
./gradlew spotlessApply                # auto-format (palantirJavaFormat for Java, ktlint for Kotlin)
./gradlew :benchmark:jmh               # JMH benchmarks against the engine

# Run services locally against a Kafka on localhost:9092
docker compose -f devops/docker-compose.yml up -d kafka
./gradlew :matching-service:run        # plain JVM app (application plugin)
./gradlew :app:bootRun                 # Spring Boot gateway on :8080

# Full stack in Docker
docker compose -f devops/docker-compose.yml up -d --build
docker compose -f devops/docker-compose.yml down
```

`build` fails on formatting violations (`spotlessCheck`); run `spotlessApply` before committing. CI (`.github/workflows/ci.yml`) is just `./gradlew build` on JDK 25.

## Architecture

Orders flow: `gateway → orders.commands (Kafka) → matching-service → orders.trades (Kafka) → gateway → WebSocket`.

The codebase is deliberately layered so the pure engine never touches Kafka or Spring.

- **`:contracts` (Java)** — the wire shared by every module. `PlaceOrderCommand`, `Trade`, `Side` (records/enum), `Topics` (the two topic names), and `WireCodec` (hand-rolled fixed-width `ByteBuffer` encode/decode — the Kafka payload format). Both Kafka topics carry raw `byte[]` values keyed by `String`; there is no JSON/Avro on the wire. Changing a record's fields means changing the byte layout in `WireCodec` in lockstep.

- **`:engine` (Java, `java-library`)** — the matching core, framework-free. `OrderBook` is a price-time-priority book: two `TreeMap<Long, Deque<Order>>` (bids reverse-ordered), `submit()` is `synchronized` and returns the trades produced. `OrderService` wraps it with a **single-writer** model: callers `place()` a command onto a bounded `BlockingQueue`, and one `matching-writer` thread drains batches, WAL-appends + `sync()`s every order, *then* matches and completes each caller's `CompletableFuture`. The WAL write happens before matching so the book can be rebuilt. Engine I/O is abstracted behind two ports: `CommandLog` (WAL) and `MarketFeedSink` (trade output). `engine` depends on `contracts` via `api` (not `implementation`) because `Trade`/`PlaceOrderCommand` appear in `OrderService`'s public signatures.

- **`:matching-service` (Kotlin, application plugin)** — hosts the engine off Kafka. `main()` is a hand-written consume loop: poll `orders.commands`, decode with `WireCodec`, `place()` into `OrderService`, then `join()` all futures → `producer.flush()` → `consumer.commitSync()` (manual commit, idempotent producer). Wires `FileCommandLog(journal.bin)` as the `CommandLog` and `KafkaMarketFeedSink` as the `MarketFeedSink`. Touches `/tmp/alive` every 5s as a container heartbeat.

- **`:app` (Kotlin + Spring Boot 4)** — the gateway. `OrderController` (`POST /order`) validates and hands to `OrderCommandPublisher`, which `WireCodec`-encodes onto `orders.commands`. The market-data side (`adapter/marketData/`) consumes `orders.trades` and fans trades out over the `/marketdata` WebSocket. Entry point `App.kt`.

- **`:benchmark` (Java + JMH)** — microbenchmarks for `OrderBook`; depends on `engine` + `contracts`.

### Durability / recovery

`FileCommandLog` is an append-only write-ahead log (`journal.bin`, framed by `WalCodec`). On startup `OrderService.recover()` replays the WAL, re-submitting every order to rebuild the book and the id counter *before* the writer thread starts. Under Compose the journal sits on a named volume so it survives restarts. There is currently no Kafka offset/WAL reconciliation — recovery is purely WAL-driven.

## Build system notes

- Convention plugins live in `buildSrc/src/main/kotlin/`: `kotlin-jvm` (JVM toolchain 25 + JUnit Platform), `spring-boot-service` (adds Spring/Kotlin-spring plugins + the `springBootEcosystem` bundle), and `spotless`. New Kotlin services should apply `buildsrc.convention.kotlin-jvm` or `spring-boot-service`; new Java libs use `java-library` + `buildsrc.convention.spotless`.
- All versions are declared in `gradle/libs.versions.toml`. Spring starter entries are intentionally version-less — the Spring Boot BOM (via `io.spring.dependency-management`) controls those versions. Precompiled convention plugins can't use the generated `libs` accessor, so `spring-boot-service` reaches the catalog through `VersionCatalogsExtension`.
- Kafka bootstrap overrides: matching service reads `KAFKA_BOOTSTRAP_SERVERS`, gateway reads `SPRING_KAFKA_BOOTSTRAP_SERVERS`; both default to `localhost:9092`.

## Conventions

- Do not write code comments (see user memory). The existing comments are sparse and explain *why*, not *what* — match that bar only when a non-obvious decision needs recording.
- `:contracts` is the single source of wire truth; a protobuf migration for the Kafka wire is planned but not done.
