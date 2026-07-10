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

- **`:matching-service` (Kotlin, application plugin)** — hosts the engine off Kafka. `MatchingRunner` (start/stop lifecycle, testable; `main()` is a thin wrapper) runs a hand-written consume loop: poll `orders.commands`, decode with `WireCodec`, `place(cmd, record.offset())` into `OrderService`, then `join()` all futures → `producer.flush()` → `consumer.commitSync()` (manual commit, idempotent producer). Wires `FileCommandLog(journal.bin)` as the `CommandLog` and `KafkaMarketFeedSink` as the `MarketFeedSink`. Touches `/tmp/alive` every 5s as a container heartbeat (only when a heartbeat path is configured). Owns the Micrometer wiring (the engine stays framework-free): a `PrometheusMeterRegistry`, the `exchange.commands.processed` counter (tagged `type=place|cancel`), JVM + Kafka-client binders, and a bare `com.sun.net.httpserver.HttpServer` serving `/metrics` on `metricsPort` (9400) — set to `null` in tests to skip the server.

- **`:app` (Kotlin + Spring Boot 4)** — the gateway. `OrderController` (`POST /order` place, `DELETE /order/{id}` cancel) validates and hands to `OrderCommandPublisher`, which `WireCodec`-encodes onto `orders.commands`. Auth is stateless JWT (Spring Security, HS256, `subject = userId`): `POST /auth/credentials/register|login` and `/auth/session/refresh|logout` are public and return access+refresh tokens; order endpoints require `Authorization: Bearer` and take `userId` from the token (not the body), while `/marketdata` and `/actuator/**` stay public. The market-data side (`adapter/marketData/`) consumes `orders.trades` and fans trades out over the `/marketdata` WebSocket. Entry point `App.kt`.

- **`:benchmark` (Java + JMH)** — microbenchmarks for `OrderBook`; depends on `engine` + `contracts`.

- **`:e2e` (Kotlin, tests only)** — full-stack test: Testcontainers Kafka + Postgres, the Spring gateway booted in-test, `MatchingRunner` in-process; register → JWT → two crossing `POST /order` → trade JSON asserted on the `/marketdata` WebSocket.

### Durability / recovery

`FileCommandLog` is an append-only write-ahead log (`journal.bin`, framed by `WalCodec`). On startup `OrderService.recover()` replays the WAL in order — re-submitting each placed order and re-applying each cancel — to rebuild the book, the id counter, and the source-offset watermark *before* the writer thread starts. Under Compose the journal sits on a named volume so it survives restarts.

Kafka↔WAL reconciliation: every WAL record carries the Kafka offset of the command it came from. Records are a tagged union keyed by a leading byte — place (`1`, 43B: `[1B kind][8B sourceOffset][8B id][8B userId][1B side][8B price][1B market][8B qty]`) and cancel (`0`, 25B: `[1B kind][8B sourceOffset][8B orderId][8B userId]`). The leading byte was repurposed from "version" to "record kind" without breaking layout: kind `1` is byte-identical to the old single-format place record, so pre-cancel journals still replay. On (re)assignment the consumer seeks to `lastSourceOffset() + 1`, so commands that were WAL-synced but whose offset commit was lost in a crash are not re-applied; the engine additionally drops any command whose offset is `<= watermark` (idempotent apply). A crash after WAL sync but before `producer.flush()` can still lose those trades/cancel events from `orders.trades` (the book stays correct; recovery never republishes) — known, deliberate gap. Unknown record kinds fail loud (`unsupported WAL record kind`); wipe the journal volume (`docker compose -f devops/docker-compose.yml down -v`) when upgrading across a format change.

### Observability

The matching service publishes Prometheus metrics on `:9400/metrics` (see `:matching-service` above). Compose runs the full pull pipeline: **Prometheus** (`prom/prometheus`, `:9090`) scrapes `matching:9400` every 15s per `devops/prometheus.yml`; **Grafana** (`:3000`, anonymous viewer) renders an orders/s dashboard. Grafana is fully file-provisioned under `devops/grafana/` — `provisioning/datasources/` defines the Prometheus datasource with a fixed `uid: prometheus`, `provisioning/dashboards/` points at `dashboards/*.json`, and the dashboard JSON references that same `uid`. The fixed uid is what makes the dashboard portable: without it the panel binds to a random per-install datasource uid and breaks on clone/reset. Note `exchange_commands_processed_total` counts commands *ingested* from Kafka, not applied — a command dropped as a duplicate (offset `<= watermark`) still increments it.

## Build system notes

- Convention plugins live in `buildSrc/src/main/kotlin/`: `kotlin-jvm` (JVM toolchain 25 + JUnit Platform), `spring-boot-service` (adds Spring/Kotlin-spring plugins + the `springBootEcosystem` bundle), and `spotless`. New Kotlin services should apply `buildsrc.convention.kotlin-jvm` or `spring-boot-service`; new Java libs use `java-library` + `buildsrc.convention.spotless`.
- All versions are declared in `gradle/libs.versions.toml`. Spring starter entries are intentionally version-less — the Spring Boot BOM (via `io.spring.dependency-management`) controls those versions. Precompiled convention plugins can't use the generated `libs` accessor, so `spring-boot-service` reaches the catalog through `VersionCatalogsExtension`.
- Kafka bootstrap overrides: matching service reads `KAFKA_BOOTSTRAP_SERVERS`, gateway reads `SPRING_KAFKA_BOOTSTRAP_SERVERS`; both default to `localhost:9092`.

## Conventions

- Do not write code comments (see user memory). The existing comments are sparse and explain *why*, not *what* — match that bar only when a non-obvious decision needs recording.
- `:contracts` is the single source of wire truth; a protobuf migration for the Kafka wire is planned but not done.
