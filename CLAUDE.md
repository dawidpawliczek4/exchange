# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

A trading-exchange matching engine, evolving into a market-simulation platform. This repo is Dawid's engineering thesis (defense Feb 2027); direction and scope live in `docs/thesis-plan.md` — **consult it before proposing new scope** (planned: ledger → leveraged perpetual → agent crowd → LLM traders; the "further work" list there is deliberately out of scope). Gradle multi-module (version catalog + `buildSrc` convention plugins), JDK 25, Java engine + Kotlin/Spring Boot services.

Docs live in `docs/` (see `docs/README.md`): `architecture.md` (deep dive: wire formats, single-writer, recovery), `operations.md` (running/deploying), `benchmarking.md` (results + methodology), `adr/` (decision records). This file is the summary; the docs are the detail.

## Commands

The `justfile` is the operational entry point — `just` lists all recipes (Compose stack, demo, kind/GKE deploys). Gradle directly:

```bash
./gradlew build                        # compile + test + spotlessCheck (what CI runs)
./gradlew test                         # all module tests
./gradlew :engine:test --tests "*OrderBookTest"   # a single test class
./gradlew spotlessApply                # auto-format (palantirJavaFormat, ktlint) — run before committing

# Local dev: services from Gradle against containers
docker compose -f devops/docker-compose.yml up -d kafka postgres
./gradlew :matching-service:run        # needs Kafka only
./gradlew :app:bootRun                 # needs Kafka AND Postgres (Flyway + ddl-auto: validate)

just compose-up / just demo / just compose-reset   # full stack in Docker
```

CI (`.github/workflows/ci.yml`) is `./gradlew build` on JDK 25; `build` fails on formatting violations.

Careful with `./gradlew :benchmark:jmh`: a bare run executes **both** harnesses, and `LatencyBenchmark`'s `@Fork(10)` adds ~30 minutes. See `docs/benchmarking.md` before running or editing benchmarks — `benchmark/results/` and `benchmark/figures/` are committed archives backing thesis numbers; never delete or overwrite them.

## Architecture

Orders flow: `gateway → orders.commands (Kafka) → matching-service → orders.trades (Kafka) → gateway → WebSocket`. The pure engine never touches Kafka or Spring. Full detail: `docs/architecture.md`.

- **`:contracts` (Java)** — the wire; single source of wire truth (protobuf migration planned, not done). Root: `Side`, `Trade`, `CancelStatus`, `Topics`. `contracts.command`: sealed `OrderCommand` (`PlaceOrderCommand` / `CancelOrderCommand` / `DepositCommand`) + `CommandCodec` (fixed-width `ByteBuffer`; type byte 0=place 27B, 1=cancel 17B, 2=deposit 17B). `contracts.event`: sealed `MarketEvent` (`TradeEvent` / `CancelEvent`, both with `seq()`/`timestamp()`) + `MarketEventCodec` (17B header `[seq][timestamp][type]`). Both topics carry raw `byte[]` keyed by `String`. Despite its name, `orders.trades` carries trades *and* cancels. Changing a record's fields means changing its codec's byte layout in lockstep.

- **`:engine` (Java, `java-library`)** — the matching core, framework-free. `OrderBook`: price-time-priority, two `TreeMap<Long, Deque<Order>>` (bids reversed), injectable `LongSupplier` clock + monotonic `seq` stamped on every event. `OrderService`: **single-writer** — callers `submit(cmd, sourceOffset)` (blocking) or `submitOffer` (non-blocking, `EngineOverloadedException` when full; **not yet wired into MatchingRunner**) onto a bounded queue; one `matching-writer` thread drains batches, WAL-appends every command, `sync()`s once per batch (group commit), *then* matches and completes the futures. WAL-before-match is the invariant recovery depends on. I/O ports: `CommandLog` (WAL) and `MarketFeedSink`. Depends on `:contracts` via `api` (not `implementation`) because contract types appear in `OrderService`'s public signatures.

- **`:matching-service` (Kotlin, application plugin)** — hosts the engine off Kafka. `MatchingRunner` (start/stop lifecycle, testable; `main()` is a thin wrapper): poll `orders.commands` (assigned partition 0), `CommandCodec.decode`, `submit(cmd, record.offset())`, `join()` all futures → `producer.flush()` → `consumer.commitSync()` (manual commit, idempotent producer). Wires `FileCommandLog(journal.bin)` (frames: `[4B len][4B crc32][payload]`; replay stops at the first corrupt frame) and `KafkaMarketFeedSink`. Owns the Micrometer wiring (engine stays framework-free): `PrometheusMeterRegistry` (common tag `application=matching-service`), `exchange.commands.processed` counter (tagged `type=place|cancel|deposit`), JVM+Kafka binders, bare `HttpServer` on `/metrics` :9400 (`metricsPort = null` in tests skips it). Touches a heartbeat file every 5s when configured.

- **`:app` (Kotlin + Spring Boot 4)** — the gateway. `OrderController` (`POST /order` → 202 pre-durability, `DELETE /order/{id}`) → `PlaceOrderService` → `KafkaOrderCommandPublisher` (`CommandCodec.encode`). Auth: stateless JWT (HS256, `subject = userId`); `/auth/credentials/*`, `/auth/session/*`, `/marketdata`, `/actuator/**` public; order endpoints take `userId` from the token, never the body. Users/sessions in Postgres (Flyway `V1__init.sql`) — the gateway won't boot without it. Market data: consumes `orders.trades`, fans out over the `/marketdata` WebSocket. **Trap:** source dirs under `src/main/kotlin/` don't mirror the `com.dawidpawliczek.app.*` packages (e.g. `src/main/kotlin/auth/`).

- **`:benchmark` (Java + JMH)** — `OrderBookBenchmark` (closed-loop throughput of the pure book) and `LatencyBenchmark` (open-loop latency via HdrHistogram, JMH as a lifecycle shell only, writes `.hlog` files to `results.dir`). Python analysis pipeline in `benchmark/analysis/` (venv; `jmh_to_csv.py`, `plot_spread.py`, `plot_latency.py`).

- **`:e2e` (Kotlin, tests only)** — Testcontainers Kafka + Postgres, gateway booted in-test, `MatchingRunner` in-process; register → JWT → two crossing orders → trade asserted on the WebSocket.

### Durability / recovery

Two deliberate layers (ADR-0004): the Kafka log (consumer decoupling/replay) and the engine WAL (deterministic state rebuild). Every WAL record carries the Kafka offset of its command. Records are a tagged union by leading byte — cancel (`0`, 25B), place (`1`, 43B: `[1B kind][8B sourceOffset][8B id][8B userId][1B side][8B price][1B market][8B qty]`); kind `1` is byte-identical to the old versionless place record, so old journals replay. On startup `OrderService.recover()` replays the WAL (rebuilding book, id counter, offset watermark) before the writer starts; the consumer then seeks `lastSourceOffset() + 1`, and the engine drops any command with offset `<= watermark` (idempotent). Known deliberate gap: a crash after WAL sync but before `producer.flush()` loses those events from `orders.trades` — the book stays correct, events are never republished, so the topic is market data, not a source of truth. Unknown WAL kinds fail loud; wipe the journal volume (`just compose-reset`) across incompatible format changes.

### Ledger / deposit — scaffolding, not functional

ADR-0001 puts balances in the single-writer hot path; the full design (decisions L1–L8, invariants, implementation order) is `docs/ledger-design.md`. Only the skeleton exists. `DepositCommand` round-trips through `CommandCodec` and is counted in metrics, but: `Ledger`/`Wallet` are empty stubs; `WalCodec.encodeDeposit` returns an empty array with no decode tag; in `writerLoop` a deposit hits the catch-all TODO — its future is **never completed** (a real deposit would hang `MatchingRunner`'s `join()`) while the watermark still advances past its offset un-logged. Unreachable today (no producer emits deposits). Treat changes here as "finish the feature per ADR-0001", not a drive-by fix.

### Observability

Prometheus (`:9090`) scrapes `matching:9400` per `devops/prometheus.yml`; Grafana (`:3000`, anonymous) renders an orders/s dashboard, file-provisioned under `devops/grafana/` with a fixed datasource `uid: prometheus` — the fixed uid is what keeps the dashboard portable; don't change it. `exchange_commands_processed_total` counts commands *ingested*, not applied (watermark-dropped duplicates still increment).

## Build system notes

- Convention plugins in `buildSrc/src/main/kotlin/`: `kotlin-jvm` (toolchain 25 + JUnit Platform), `spring-boot-service` (Spring/Kotlin-spring plugins + `springBootEcosystem` bundle), `spotless`. New Kotlin services apply `buildsrc.convention.kotlin-jvm` or `spring-boot-service`; new Java libs use `java-library` + `buildsrc.convention.spotless`.
- Versions in `gradle/libs.versions.toml`; Spring starter entries are intentionally version-less (Spring Boot BOM controls them). Precompiled convention plugins can't use the generated `libs` accessor, so `spring-boot-service` goes through `VersionCatalogsExtension`.
- Kafka bootstrap overrides: matching reads `KAFKA_BOOTSTRAP_SERVERS`, gateway reads `SPRING_KAFKA_BOOTSTRAP_SERVERS`; both default `localhost:9092`.
- Deploy targets beyond Compose: kustomize base + `local`/`gcp` overlays in `devops/k8s/` (Strimzi Kafka), Terraform for the GKE cluster in `infra/gcp/`. The GCP image registry/tag is hardcoded in **two** places that must match: `justfile` and `devops/k8s/overlays/gcp/kustomization.yaml`. See `docs/operations.md`.

## Conventions

- Do not write code comments (see user memory). The existing comments are sparse and explain *why*, not *what* — match that bar only when a non-obvious decision needs recording.
- `:contracts` is the single source of wire truth; a protobuf migration for the Kafka wire is planned but not done.
- Load-bearing decisions get an ADR in `docs/adr/` (template in its README).
- All docs are in English; the thesis itself is written in Polish, separately.
