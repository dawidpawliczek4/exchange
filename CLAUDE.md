# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

A trading-exchange matching engine, evolving into a market-simulation platform. This repo is Dawid's engineering thesis (defense Feb 2027); direction and scope live in `docs/thesis-plan.md` — **consult it before proposing new scope** (planned: ledger → leveraged perpetual → agent crowd → LLM traders; the "further work" list there is deliberately out of scope). Gradle multi-module (version catalog + `buildSrc` convention plugins), JDK 25, Java engine + Kotlin/Spring Boot services.

Docs live in `docs/` (see `docs/README.md`): `architecture.md` (deep dive: wire formats, single-writer, recovery), `operations.md` (running/deploying), `benchmarking.md` (results + methodology), `testing.md` (test layers and conventions — match them when writing tests), `adr/` (decision records). This file is the summary; the docs are the detail.

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
./gradlew :agent-crowd:run             # ZI bot crowd against Kafka; BOT_COUNT/MID_PRICE/PRICE_BAND/SEED env

just compose-up / just demo / just compose-reset   # full stack in Docker
```

CI (`.github/workflows/ci.yml`) is `./gradlew build` on JDK 25; `build` fails on formatting violations.

Careful with `./gradlew :benchmark:jmh`: a bare run executes **both** harnesses, and `LatencyBenchmark`'s `@Fork(10)` adds ~30 minutes. See `docs/benchmarking.md` before running or editing benchmarks — `benchmark/results/` and `benchmark/figures/` are committed archives backing thesis numbers; never delete or overwrite them.

## Architecture

Orders flow: `gateway | agent-crowd → orders.commands (Kafka) → matching-service → orders.trades (Kafka) → gateway → WebSocket`. The matching-service also publishes private per-user events to `account.events` (keyed by `userId`; no consumer yet). The pure engine never touches Kafka or Spring. Full detail: `docs/architecture.md`.

- **`:contracts` (Java)** — the wire; single source of wire truth (protobuf migration planned, not done). Root: `Side`, `Trade`, `CancelStatus`, `Asset` (`QUOTE`=cash / `BASE`=token, 1B on the wire), `RejectReason`, `Topics`. `contracts.command`: sealed `OrderCommand` (`PlaceOrderCommand` / `CancelOrderCommand` / `DepositCommand` with asset) + `CommandCodec` (fixed-width `ByteBuffer`; type byte 0=place 27B, 1=cancel 17B, 2=deposit 18B). `contracts.event`: sealed `MarketEvent` (`TradeEvent` / `CancelEvent`, both with `seq()`/`timestamp()`) + `MarketEventCodec` (17B header `[seq][timestamp][type]`), and sealed `AccountEvent` (`DepositAccepted` / `DepositRejected` with asset, `OrderRejected` with reason; all with `userId()`) + `AccountEventCodec` (same header, 26B total) for the private `account.events` topic. All topics carry raw `byte[]` keyed by `String`. Despite its name, `orders.trades` carries trades *and* cancels. Changing a record's fields means changing its codec's byte layout in lockstep.

- **`:engine` (Java, `java-library`)** — the matching core, framework-free. `OrderBook`: price-time-priority, two `TreeMap<Long, Deque<Order>>` (bids reversed), no clock — `submit`/`cancel` take the command's `timestamp` as an argument — plus a monotonic `seq` stamped on every event. `OrderService`: **single-writer** — callers `submit(cmd, sourceOffset)` (blocking) or `submitOffer` (non-blocking, `EngineOverloadedException` when full; **not yet wired into MatchingRunner**) onto a bounded queue; one `matching-writer` thread drains batches, samples the injectable clock once per command and WAL-appends every command with that timestamp (including places that will fail the funds check — ADR-0005), `sync()`s once per batch (group commit), *then* applies through one shared `apply` path (also used by `recover()`), passing the command's WAL timestamp as an argument into every book/ledger call so every event a command produces carries it: place = reserve (BUY: `price×qty` cash, market BUY priced via `OrderBook.calculateMarketOrderValue`; SELL: `qty` token; failure → private `OrderRejected`, book untouched) → submit → settle per fill (+ price-improvement release for taker-buyer limits, market remainder release); cancel = release the remainder (book's `CancelResult` hands back the removed `Order`); deposit = credit. Futures are `CompletableFuture<Void>` — completion barriers, not result carriers; events travel only through the sinks. WAL-before-match is the invariant recovery depends on. `Ledger`: in-memory `HashMap<Long, Wallet>` (`cash` + `asset`), own event `seq`, timestamp passed in like the book; reservations **implicit** (reserve subtracts, release adds back, settle credits only receiving legs; no `reserved` field — the book is the reservation registry, L3); all rejections deterministic and non-throwing (an exception escaping the writer loop = engine fail-stop). I/O ports: `CommandLog` (WAL), `MarketFeedSink` (public), `AccountFeedSink` (private, per-user). Depends on `:contracts` via `api` (not `implementation`) because contract types appear in the engine's public API.

- **`:matching-service` (Kotlin, application plugin)** — hosts the engine off Kafka. `MatchingRunner` (start/stop lifecycle, testable; `main()` is a thin wrapper): poll `orders.commands` (assigned partition 0), `CommandCodec.decode`, `submit(cmd, record.offset())`, `join()` all futures → `producer.flush()` → `consumer.commitSync()` (manual commit, idempotent producer). Wires `FileCommandLog(journal.bin)` (frames: `[4B len][4B crc32][payload]`; replay stops at the first corrupt frame), `KafkaMarketFeedSink` (constant key) and `KafkaAccountFeedSink` (`account.events`, keyed by `userId`) — both on one producer, so the pre-commit `flush()` covers both topics. Owns the Micrometer wiring (engine stays framework-free): `PrometheusMeterRegistry` (common tag `application=matching-service`), `exchange.commands.processed` counter (tagged `type=place|cancel|deposit`), JVM+Kafka binders, bare `HttpServer` on `/metrics` :9400 (`metricsPort = null` in tests skips it). Touches a heartbeat file every 5s when configured.

- **`:app` (Kotlin + Spring Boot 4)** — the gateway. `OrderController` (`POST /order` → 202 pre-durability, `DELETE /order/{id}`) → `PlaceOrderService` → `KafkaOrderCommandPublisher` (`CommandCodec.encode`). Auth: stateless JWT (HS256, `subject = userId`); `/auth/credentials/*`, `/auth/session/*`, `/marketdata`, `/actuator/**` public; order endpoints take `userId` from the token, never the body. Users/sessions in Postgres (Flyway `V1__init.sql`) — the gateway won't boot without it. Market data: consumes `orders.trades`, fans out over the `/marketdata` WebSocket.

- **`:agent-crowd` (Kotlin, application plugin)** — the bot crowd (thesis D11/D12), first cut: `AgentCrowd.kt` spawns `BOT_COUNT` zero-intelligence bots on virtual threads, each funds its own account with two `DepositCommand`s (QUOTE + BASE) and then loops placing random limit orders (side 50/50, price uniform in `[MID_PRICE − PRICE_BAND, MID_PRICE + PRICE_BAND]`, qty 1–10, 50–500 ms pause). Bypasses the gateway entirely: raw `KafkaProducer` → `CommandCodec.encode` → `orders.commands`, unkeyed like the gateway. Bot `userId`s start at 1 000 000 so they never collide with registered users. Seeded (`SEED` → one `SplittableRandom` split per bot). No consumer, no cancels — bots can't learn the ids of resting orders (the engine assigns ids and only trades/cancels carry them), which is the known gap to close before a market maker: planned fix is an `OrderAccepted(userId, orderId)` event on `account.events`. Env: `KAFKA_BOOTSTRAP_SERVERS`, `BOT_COUNT`, `MID_PRICE`, `PRICE_BAND`, `SEED`. In Compose as service `crowd` (`devops/agent-crowd.Dockerfile`, starts with the stack; knobs interpolated from the shell, e.g. `BOT_COUNT=1000 just compose-up`); not in the k8s manifests yet.

- **`:benchmark` (Java + JMH)** — `OrderBookBenchmark` (closed-loop throughput of the pure book) and `LatencyBenchmark` (open-loop latency via HdrHistogram, JMH as a lifecycle shell only, writes `.hlog` files to `results.dir`). Python analysis pipeline in `benchmark/analysis/` (venv; `jmh_to_csv.py`, `plot_spread.py`, `plot_latency.py`).

- **`:e2e` (Kotlin, tests only)** — Testcontainers Kafka + Postgres, gateway booted in-test, `MatchingRunner` in-process; register → JWT → two crossing orders → trade asserted on the WebSocket.

### Durability / recovery

Two deliberate layers (ADR-0004): the Kafka log (consumer decoupling/replay) and the engine WAL (deterministic state rebuild). Every WAL record carries the Kafka offset of its command. Records are a tagged union by leading byte — cancel (`0`, 33B), place (`1`, 51B: `[1B kind][8B sourceOffset][8B timestamp][8B id][8B userId][1B side][8B price][1B market][8B qty]`), deposit (`2`, 34B: `[1B kind][8B sourceOffset][8B timestamp][8B userId][1B asset][8B quantity]`); journals from before the timestamp field don't replay (no legacy decoding, wipe the volume). On startup `MatchingRunner` reads the last record of `orders.trades` and `account.events` (`KafkaFeedWatermark`: `seq` = first 8 header bytes) and passes the two published-`seq` watermarks to the `OrderService` constructor; `recover()` replays the WAL through the same `apply` path as live processing (rebuilding book, ledger, id counter, offset watermark — reproducing NSF rejections) and **republishes every event with `seq` above its stream's watermark** (market and account counters are independent), byte-identical thanks to the WAL timestamps — ADR-0006; the runner flushes, the consumer seeks `lastSourceOffset() + 1`, and the engine drops any command with offset `<= watermark` (idempotent). This closes ADR-0004's crash-between-sync-and-flush gap and rests on each feed topic being an ordered prefix of its stream (constant key, `partitions: 1`, idempotent producer) — splitting the topics into partitions breaks it. Unknown WAL kinds fail loud; wipe the journal volume (`just compose-reset`) across incompatible format changes.

### Ledger — engine-complete, unreachable from outside

ADR-0001 puts balances in the single-writer hot path; design history (decisions L1–L8, invariants) is `docs/ledger-design.md`, with two recorded deviations: NSF verdict happens at apply, not before the WAL append (ADR-0005 supersedes L5 — determinism on in-batch dependencies), and reservations are implicit rather than `available`+`reserved` fields (L3's "book is the reservation registry" taken literally). The full flow works and is crash-safe: deposits (QUOTE/BASE), reserve on place with NSF/overflow → `OrderRejected` on `account.events`, settle per fill with price-improvement release, release on cancel and for market-order remainders, all replayed through the shared `apply` path on recovery. Covered by `LedgerTest`, `OrderServiceTest` (settlement scenarios + `Recovery`), and `MatchingRunnerIntegrationTest` (deposit + NSF on the account topic, republish after a simulated crash before flush). **Tests must seed balances first** (`seed(...)` helpers; e2e and the agent crowd produce `DepositCommand`s with a raw producer) because unfunded places are rejected, and recovery tests with an in-memory sink receive the replayed events (assert or drain them). What's missing sits outside the engine: no gateway endpoint produces deposits (L8 deposit-on-registration), nothing consumes `account.events`, no balance projection (LED-4) — that's the next step, along with treasury (L7) and jqwik property tests for the invariants.

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
