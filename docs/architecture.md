# Architecture

How the exchange is put together: module boundaries, the order path, wire formats, the
single-writer engine, and durability/recovery. For how to *run* it, see
[operations.md](operations.md); for *why* the load-bearing decisions were made, see the
[ADRs](adr/).

## System flow

```
gateway (:app) · bot crowd (:agent-crowd)
  → Kafka orders.commands
  → matching-service  [decode → OrderService (WAL → match/ledger) → events]
  → Kafka orders.trades   (market events) → gateway → /marketdata WebSocket
  → Kafka account.events  (private account events, keyed by userId; no consumer yet)
```

The layering rule: the pure engine never touches Kafka or Spring. `:contracts` defines the
wire, `:engine` is framework-free Java behind two ports, and the services adapt it to
Kafka/HTTP.

## Modules

| Module | Language | Role |
|---|---|---|
| `:contracts` | Java | Wire truth: shared types + the two Kafka codecs |
| `:engine` | Java (`java-library`) | Matching core: order book, single-writer service, WAL codec |
| `:matching-service` | Kotlin (application) | Hosts the engine off Kafka; owns metrics |
| `:app` | Kotlin + Spring Boot 4 | Gateway: REST + JWT in, WebSocket market data out |
| `:agent-crowd` | Kotlin (application) | Bot crowd: seeded zero-intelligence traders producing straight to Kafka |
| `:benchmark` | Java + JMH | Engine measurement harnesses ([benchmarking.md](benchmarking.md)) |
| `:e2e` | Kotlin (tests only) | Full-stack test: Testcontainers Kafka + Postgres, gateway + runner in-process |

Dependency rules: `:engine` depends on `:contracts` via `api` (not `implementation`)
because contract types appear in the engine's public API (`OrderCommand` in
`OrderService.submit`, `MarketEvent`/`AccountEvent` in the sink ports). The
services depend on `:engine`/`:contracts`; nothing depends on the services.

## `:contracts` — the wire

All Kafka topics carry raw `byte[]` values keyed by `String`; there is no JSON/Avro on
the wire (a protobuf migration is planned but not done). Root package holds the shared
types (`Side`, `Trade`, `CancelStatus`, `Asset` (`QUOTE` = cash / `BASE` = the traded
token, 1 byte on the wire), `RejectReason`, `Topics` with `orders.commands` /
`orders.trades` / `account.events`); the codecs live in per-direction subpackages:

**`contracts.command`** — sealed `OrderCommand` (`PlaceOrderCommand`,
`CancelOrderCommand`, `DepositCommand`) + `CommandCodec` for the `orders.commands` topic.
Fixed-width, first byte is the type tag:

| Command | Type | Size | Layout |
|---|---|---|---|
| place | 0 | 27B | `[1B type][8B userId][1B side][8B price][1B market][8B qty]` |
| cancel | 1 | 17B | `[1B type][8B userId][8B id]` |
| deposit | 2 | 18B | `[1B type][8B userId][1B asset][8B quantity]` |

**`contracts.event`** — sealed `MarketEvent` (`TradeEvent`, `CancelEvent`; both carry
`seq()` and `timestamp()`) + `MarketEventCodec` for the `orders.trades` topic. 17B header
`[8B seq][8B timestamp][1B type]`, then the body:

| Event | Type | Body |
|---|---|---|
| trade | 0 | 48B: makerId, makerUserId, takerId, takerUserId, price, quantity (6 longs) |
| cancel | 1 | 17B: `[8B userId][8B orderId][1B status]` (0 = CANCELED, 1 = REJECTED) |

Despite its name, `orders.trades` carries the union of trade *and* cancel events — it is
the market-event stream, not a trades-only topic.

Alongside `MarketEvent` sits the sealed `AccountEvent` (`DepositAccepted`,
`DepositRejected`, `OrderRejected`; `seq()`, `timestamp()`, `userId()`) +
`AccountEventCodec` for the `account.events` topic — the **private** per-user stream,
deliberately separate from the public market feed. Same 17B header, 9B body (26B total):
deposits carry `[8B userId][1B asset]` (types 0 = accepted, 1 = rejected),
`OrderRejected` carries `[8B userId][1B reason]` (type 2; `RejectReason.NSF` for both
insufficient funds and unpriceable orders). Records are keyed by `userId` (per-user
ordering via the default partitioner), where `orders.trades` uses a constant key.
`AccountEvent.seq` comes from the `Ledger`'s own counter — a numbering stream
independent of the book's.

Changing a record's fields means changing the corresponding codec's byte layout in
lockstep; codec round-trips are covered by `CommandCodecTest` / `MarketEventCodecTest` /
`AccountEventCodecTest`.

## `:engine` — the matching core

### OrderBook (`domain/`)

Price-time-priority limit order book: two `NavigableMap<Long, Deque<Order>>` (`TreeMap`,
bids reverse-ordered). `submit(Order)` matches against the opposite side while prices
cross, emits one `TradeEvent` per fill at the resting price, and rests any remainder
(market orders never rest — the unfilled remainder is dropped). `cancel(orderId, userId)`
is a linear scan over both sides and returns a `CancelResult`: the `CancelEvent`
(`CANCELED` if the order was found and removed, `REJECTED` otherwise) plus the removed
`Order` itself (`null` on reject) — the domain fact the ledger release is computed from.
`calculateMarketOrderValue(qty)` walks the asks and prices the executable part of a
market buy (overflow → `-1`, treated as unaffordable); it is a read-only query, the book
knows nothing about the ledger.

The book stamps every event with a monotonically increasing `seq` and the `timestamp`
its caller passes into `submit`/`cancel` — the book has no clock of its own; the
timestamp is the command's, sampled once by `OrderService` and written to the WAL. That
is the source of `MarketEvent.seq()/timestamp()`. Methods are `synchronized`, but in
production only the single writer thread calls them.

### OrderService (`application/`) — the single-writer model

Callers hand commands to `OrderService` and get a `CompletableFuture<Void>` back — a
pure completion barrier ("durable and applied"), not a result carrier; events travel
through the sinks, and exceptional completion is what stops `MatchingRunner` from
committing offsets after a writer failure:

- `submit(cmd, sourceOffset)` — blocking `queue.put` onto a bounded
  `ArrayBlockingQueue` (capacity 65 536).
- `submitOffer(cmd, sourceOffset)` — non-blocking `queue.offer`; a full queue fails the
  future with `EngineOverloadedException`. (Exists for load-shedding; **not yet wired**
  into `MatchingRunner`, which still uses blocking `submit`.)

One `matching-writer` thread drains batches and, per batch:

1. **Dedup** — any job whose `sourceOffset <= watermark` completes immediately
   (idempotent re-apply after seek/restart).
2. **WAL append** — every surviving command is encoded (`WalCodec`) and appended to the
   `CommandLog`, unconditionally — including places that will fail the funds check
   ([ADR-0005](adr/0005-nsf-verdict-at-apply.md)). Order ids are assigned here, before
   logging, so replay reproduces them; so is the command's timestamp (one clock sample
   per command, passed as an argument into every book/ledger call it makes, so every
   event it produces carries it), so replay reproduces those too.
3. **`sync()`** — one fsync per batch (group commit), then the source-offset watermark
   advances.
4. **Apply + publish** — only now does each command take effect, through one shared
   `apply` path (also used by `recover()`): a place reserves funds first (BUY: `price ×
   qty` cash, market BUY priced from the book; SELL: `qty` of the token) — on failure
   the engine emits a private `OrderRejected` and the book is never touched; on success
   the order hits the book, each fill settles both legs (plus a price-improvement
   release for a taker-buyer's limit), a market order's unfilled remainder is released;
   a cancel releases the remainder's reservation; a deposit credits the ledger. Market
   events → `MarketFeedSink`, account events → `AccountFeedSink`, then each caller's
   future completes.

WAL-before-match is the core invariant: a command is durable before it can have any
effect, so replaying the WAL rebuilds the exact book state. Any exception in the writer
loop fail-stops the engine (`running = false`) and completes all queued futures
exceptionally.

### Ports (`ports/`)

- `CommandLog` — `append(byte[])`, `sync()`, `replay(Consumer<byte[]>)`. The WAL.
- `MarketFeedSink` — `publish(List<MarketEvent>)`. The public market-event output.
- `AccountFeedSink` — `publish(List<AccountEvent>)`. The private per-user event output.

Production adapters live in `:matching-service`; tests and benchmarks plug in in-memory
ones.

### WAL format (`wire/`)

`WalRecord` is a sealed tagged union (`PlaceRecord`, `CancelRecord`, `DepositRecord`)
encoded by `WalCodec`; the leading byte is the record kind:

| Record | Tag | Size | Layout |
|---|---|---|---|
| cancel | 0 | 33B | `[1B tag][8B sourceOffset][8B timestamp][8B orderId][8B userId]` |
| place | 1 | 51B | `[1B tag][8B sourceOffset][8B timestamp][8B id][8B userId][1B side][8B price][1B market][8B qty]` |
| deposit | 2 | 34B | `[1B tag][8B sourceOffset][8B timestamp][8B userId][1B asset][8B quantity]` |

The timestamp field was added in place (same tags, 8 bytes longer), so journals written
before it are not readable — there is no production data to migrate, wipe the volume.
Unknown kinds fail loud (`unsupported WAL record kind`); upgrading across an
incompatible format change means wiping the journal volume
(`docker compose -f devops/docker-compose.yml down -v`).

Every record carries the Kafka offset of the command it came from and the timestamp the
writer sampled for it at append time — see
[Durability and recovery](#durability-and-recovery).

### Ledger

The ledger (per [ADR-0001](adr/0001-ledger-in-hot-path.md): balances in the
single-writer hot path; design history in [ledger-design.md](ledger-design.md)) is
complete inside the engine: deposits, reservations on place, settlement on trade,
releases on cancel, NSF rejections — all crash-safe through the shared apply path.

`Ledger` holds `HashMap<Long, Wallet>` (`Wallet` = `cash` + `asset` balance, one spot
pair) with its own event `seq` counter; like `OrderBook` it has no clock and stamps
events with the timestamp its caller passes in.
Reservations are **implicit**: `reserveCash/reserveAsset` subtract from the balance
(returning `false` — never throwing — on insufficient or negative amounts),
`releaseCash/releaseAsset` add back, and `settle(buyer, seller, price, qty)` credits
only the receiving legs (the paying legs were taken at reserve time). There is no
`reserved` field — the book itself is the reservation registry (design decision L3):
the reserved amount is derivable as `remaining × limit` over open orders. Tests
therefore verify by exact balance arithmetic per scenario; a generic cross-system
conservation property (`Σ balances + Σ book reservations = Σ accepted deposits`) is the
planned property-test oracle (see `docs/testing.md` backlog).

Deterministic rejections (`DepositRejected` for `quantity <= 0`/overflow,
`OrderRejected` for NSF/unpriceable) mean replay decides identically —
[ADR-0005](adr/0005-nsf-verdict-at-apply.md) covers why rejected places still reach the
WAL. Outside the engine the flow is not reachable yet: no gateway endpoint produces
`DepositCommand`s and nothing consumes `account.events` (tests seed balances by
producing deposits directly).

## `:matching-service` — hosting the engine

`MatchingRunner` (start/stop lifecycle, integration-testable; `MatchingService.main()` is
a thin wrapper) runs a hand-written consume loop:

```
poll orders.commands (partition 0) → CommandCodec.decode → orderService.submit(cmd, offset)
  → join all futures → producer.flush() → consumer.commitSync()
```

The consumer `assign`s partition 0 explicitly (single-partition topic assumption), uses
manual commits, `auto.offset.reset=earliest`, and an idempotent producer. Before the
engine is built, `KafkaFeedWatermark` reads the last record of `orders.trades` and of
`account.events` (partition 0, `seq` from the first 8 header bytes, no decoding) and hands
the two watermarks to `OrderService`, which republishes whatever the WAL has above them
([ADR-0006](adr/0006-recovery-republishes-unpublished-events.md)); the runner flushes
once, then seeks to `lastSourceOffset() + 1` from the recovered WAL.

Adapters: `FileCommandLog` (the WAL — each record framed as
`[4B length][4B crc32][payload]`; replay stops at the first torn or corrupt frame, which
makes a partially written tail harmless), `KafkaMarketFeedSink` (market events →
`orders.trades`, constant key) and `KafkaAccountFeedSink` (account events →
`account.events`, keyed by `userId`). Both sinks share one producer, so the existing
`flush()`-before-commit covers both topics. `InMemoryCommandLog` and
`DummyMarketFeedSink` exist for tests.

The runner also owns everything the engine deliberately doesn't: a
`PrometheusMeterRegistry` (common tag `application=matching-service`), the
`exchange.commands.processed` counter (tagged `type=place|cancel|deposit`), JVM-GC and
Kafka-client binders, a bare `com.sun.net.httpserver.HttpServer` serving `/metrics` on
`metricsPort` (9400 in production, `null` in tests to skip the server), and a `/tmp/alive`
heartbeat touch every 5s when a heartbeat path is configured (container liveness).

## `:app` — the gateway

Package root is `com.dawidpawliczek.app`; source directories under
`src/main/kotlin/com/dawidpawliczek/app/` mirror it (as do the tests).

- **Orders** (`order/`, hexagonal: `adapter/inbound|outbound`, `application/port|service`):
  `OrderController` (`POST /order`, `DELETE /order/{id}`) validates and hands to
  `PlaceOrderService`, which publishes via `KafkaOrderCommandPublisher`
  (`CommandCodec.encode` → `orders.commands`). `POST /order` returns 202 *before* the
  command is durable — an "acknowledged after durable" path is planned (thesis decision
  D10).
- **Auth** (`auth/`): stateless JWT (Spring Security, HS256, `subject = userId`).
  `POST /auth/credentials/register|login` and `/auth/session/refresh|logout` are public
  and return access+refresh tokens; order endpoints require `Authorization: Bearer` and
  take `userId` from the token, never the body. `/marketdata` and `/actuator/**` are
  public. Users/credentials/sessions live in Postgres via Flyway
  (`db/migration/V1__init.sql`, `ddl-auto: validate`) — the gateway won't boot without a
  reachable database.
- **Market data** (`marketData/`): `KafkaMarketFeedSubscriber` consumes `orders.trades`
  (unique group id per instance, `auto-offset-reset: latest`) and fans events out over
  the `/marketdata` WebSocket via `WebSocketBroadcaster`.

## `:agent-crowd` — the bot crowd

The algorithmic crowd from the thesis plan (D11: one process, virtual threads, ABIDES
proportions; D12: same port for Kafka and in-process modes). What exists is the first
cut — zero-intelligence traders only, Kafka mode only, no port abstraction yet.

`AgentCrowd.kt` (`main`) spawns `BOT_COUNT` `ZeroIntelligenceBot`s, one virtual thread
each, sharing a single idempotent `KafkaProducer`. A bot first funds itself with two
`DepositCommand`s (1 000 000 000 QUOTE, 1 000 000 BASE — large enough that NSF never
fires) and then loops: random limit order (side 50/50, price uniform in
`[MID_PRICE − PRICE_BAND, MID_PRICE + PRICE_BAND]`, quantity 1–10), sleep 50–500 ms.
Deposits land before the bot's orders because one producer on one partition preserves
order. Records are unkeyed, like the gateway's. Bot `userId`s start at 1 000 000 so they
never collide with users registered through the gateway — the crowd bypasses the gateway
and JWT altogether, so it needs no Postgres.

Determinism: `SEED` seeds one `SplittableRandom`; each bot gets its own `split()`, so
the same seed reproduces the same per-bot decision sequence regardless of scheduling.
(The resulting market is still not bit-reproducible — inter-arrival timing on real
threads is not.)

Known gap before a market maker: bots never learn the ids of their resting orders.
`PlaceOrderCommand` carries no id, the engine assigns one, and only `Trade`
(maker/taker ids) and `CancelEvent` expose it — an order that never trades has an id its
owner cannot know, so it cannot be cancelled. The intended fix is an
`OrderAccepted(userId, orderId)` variant on `account.events`, which the crowd would be
the first consumer of (it also needs `OrderRejected` there).

## Durability and recovery

Two deliberate durability layers ([ADR-0004](adr/0004-two-durability-layers.md)): the
Kafka log decouples producers/consumers and gives replay to downstream consumers; the
engine's own WAL gives deterministic single-threaded state reconstruction. The Kafka
offset stored in every WAL record stitches them together idempotently.

On startup `OrderService.recover()` replays the WAL in order through the **same `apply`
path as live processing** — re-running each place's reserve/match/settle (reproducing
NSF rejections, [ADR-0005](adr/0005-nsf-verdict-at-apply.md)), each cancel's release,
and each deposit — rebuilding book, ledger, id counter, and the source-offset watermark
*before* the writer thread starts. Replayed events are compared against the two
published-`seq` watermarks the runner read from Kafka (market and account streams have
independent counters) and everything above them is republished through the normal sinks
([ADR-0006](adr/0006-recovery-republishes-unpublished-events.md)); because the WAL
carries the command timestamp, the republished bytes equal what the crashed run would
have sent. On (re)assignment the consumer seeks to
`lastSourceOffset() + 1`, so commands that were WAL-synced but whose offset commit was
lost in a crash are not re-consumed; the engine additionally drops any command whose
offset is `<= watermark` (idempotent apply), covering re-delivery inside one process
lifetime.

The crash window that used to lose events — after WAL `sync()`, before
`producer.flush()` — is therefore closed on the next start. This rests on each feed
topic being an ordered prefix of its event stream: constant key on `orders.trades`,
`partitions: 1` on both topics, idempotent producer, single publishing thread. Splitting
either topic into several partitions breaks the watermark read and needs a new ADR. The
topic being complete does not make it the authority on money: the ledger is
([ADR-0001](adr/0001-ledger-in-hot-path.md)).

## Observability

Prometheus scrapes `matching:9400` every 15s (`devops/prometheus.yml`); Grafana renders
an orders/s dashboard, fully file-provisioned under `devops/grafana/` with a **fixed
datasource `uid: prometheus`** — the fixed uid is what makes the dashboard portable;
without it the panel binds to a random per-install datasource uid and breaks on
clone/reset.

Note: `exchange_commands_processed_total` counts commands *ingested* from Kafka, not
applied — a duplicate dropped by the watermark check still increments it.
