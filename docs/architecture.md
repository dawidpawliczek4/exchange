# Architecture

How the exchange is put together: module boundaries, the order path, wire formats, the
single-writer engine, and durability/recovery. For how to *run* it, see
[operations.md](operations.md); for *why* the load-bearing decisions were made, see the
[ADRs](adr/).

## System flow

```
gateway (:app)
  → Kafka orders.commands
  → matching-service  [decode → OrderService (WAL → match) → market events]
  → Kafka orders.trades
  → gateway → /marketdata WebSocket
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
| `:benchmark` | Java + JMH | Engine measurement harnesses ([benchmarking.md](benchmarking.md)) |
| `:e2e` | Kotlin (tests only) | Full-stack test: Testcontainers Kafka + Postgres, gateway + runner in-process |

Dependency rules: `:engine` depends on `:contracts` via `api` (not `implementation`)
because `MarketEvent`/`OrderCommand` appear in `OrderService`'s public signatures. Both
services depend on `:engine`/`:contracts`; nothing depends on the services.

## `:contracts` — the wire

Both Kafka topics carry raw `byte[]` values keyed by `String`; there is no JSON/Avro on
the wire (a protobuf migration is planned but not done). Root package holds the shared
types (`Side`, `Trade`, `CancelStatus`, `Topics` with `orders.commands` /
`orders.trades`); the codecs live in per-direction subpackages:

**`contracts.command`** — sealed `OrderCommand` (`PlaceOrderCommand`,
`CancelOrderCommand`, `DepositCommand`) + `CommandCodec` for the `orders.commands` topic.
Fixed-width, first byte is the type tag:

| Command | Type | Size | Layout |
|---|---|---|---|
| place | 0 | 27B | `[1B type][8B userId][1B side][8B price][1B market][8B qty]` |
| cancel | 1 | 17B | `[1B type][8B userId][8B id]` |
| deposit | 2 | 17B | `[1B type][8B userId][8B quantity]` |

**`contracts.event`** — sealed `MarketEvent` (`TradeEvent`, `CancelEvent`; both carry
`seq()` and `timestamp()`) + `MarketEventCodec` for the `orders.trades` topic. 17B header
`[8B seq][8B timestamp][1B type]`, then the body:

| Event | Type | Body |
|---|---|---|
| trade | 0 | 48B: makerId, makerUserId, takerId, takerUserId, price, quantity (6 longs) |
| cancel | 1 | 17B: `[8B userId][8B orderId][1B status]` (0 = CANCELED, 1 = REJECTED) |

Despite its name, `orders.trades` carries the union of trade *and* cancel events — it is
the market-event stream, not a trades-only topic.

Changing a record's fields means changing the corresponding codec's byte layout in
lockstep; codec round-trips are covered by `CommandCodecTest` / `MarketEventCodecTest`.

## `:engine` — the matching core

### OrderBook (`domain/`)

Price-time-priority limit order book: two `NavigableMap<Long, Deque<Order>>` (`TreeMap`,
bids reverse-ordered). `submit(Order)` matches against the opposite side while prices
cross, emits one `TradeEvent` per fill at the resting price, and rests any remainder
(market orders never rest — the unfilled remainder is dropped). `cancel(orderId, userId)`
is a linear scan over both sides and always returns a `CancelEvent` — `CANCELED` if the
order was found and removed, `REJECTED` otherwise.

The book stamps every event with a monotonically increasing `seq` and a timestamp from an
injectable `LongSupplier clock` (defaults to `System::currentTimeMillis`) — that is the
source of `MarketEvent.seq()/timestamp()`. Methods are `synchronized`, but in production
only the single writer thread calls them.

### OrderService (`application/`) — the single-writer model

Callers hand commands to `OrderService` and get a
`CompletableFuture<List<MarketEvent>>` back:

- `submit(cmd, sourceOffset)` — blocking `queue.put` onto a bounded
  `ArrayBlockingQueue` (capacity 65 536).
- `submitOffer(cmd, sourceOffset)` — non-blocking `queue.offer`; a full queue fails the
  future with `EngineOverloadedException`. (Exists for load-shedding; **not yet wired**
  into `MatchingRunner`, which still uses blocking `submit`.)

One `matching-writer` thread drains batches and, per batch:

1. **Dedup** — any job whose `sourceOffset <= watermark` completes immediately with an
   empty event list (idempotent re-apply after seek/restart).
2. **WAL append** — every surviving command is encoded (`WalCodec`) and appended to the
   `CommandLog`. Order ids are assigned here, before logging, so replay reproduces them.
3. **`sync()`** — one fsync per batch (group commit), then the source-offset watermark
   advances.
4. **Match + publish** — only now does each order hit the book; resulting events go to
   the `MarketFeedSink` and complete each caller's future.

WAL-before-match is the core invariant: a command is durable before it can have any
effect, so replaying the WAL rebuilds the exact book state. Any exception in the writer
loop fail-stops the engine (`running = false`) and completes all queued futures
exceptionally.

### Ports (`ports/`)

- `CommandLog` — `append(byte[])`, `sync()`, `replay(Consumer<byte[]>)`. The WAL.
- `MarketFeedSink` — `publish(List<MarketEvent>)`. The event output.

Production adapters live in `:matching-service`; tests and benchmarks plug in in-memory
ones.

### WAL format (`wire/`)

`WalRecord` is a sealed tagged union (`PlaceRecord`, `CancelRecord`, `DepositRecord`)
encoded by `WalCodec`; the leading byte is the record kind:

| Record | Tag | Size | Layout |
|---|---|---|---|
| cancel | 0 | 25B | `[1B tag][8B sourceOffset][8B orderId][8B userId]` |
| place | 1 | 43B | `[1B tag][8B sourceOffset][8B id][8B userId][1B side][8B price][1B market][8B qty]` |
| deposit | — | — | not implemented: `encodeDeposit` returns an empty array and `decode` has no deposit tag |

The tag byte was repurposed from "version" to "record kind" without breaking layout: kind
1 is byte-identical to the old single-format place record, so pre-cancel journals still
replay. Unknown kinds fail loud (`unsupported WAL record kind`); upgrading across an
incompatible format change means wiping the journal volume
(`docker compose -f devops/docker-compose.yml down -v`).

Every record carries the Kafka offset of the command it came from — see
[Durability and recovery](#durability-and-recovery).

### Ledger / deposit flow — scaffolding, not functional

The deposit flow (per [ADR-0001](adr/0001-ledger-in-hot-path.md): balances in the
single-writer hot path; full design in [ledger-design.md](ledger-design.md)) is
scaffolded end-to-end but **not implemented**. What works:
`DepositCommand` round-trips through `CommandCodec`, the sealed hierarchies admit it, and
`MatchingRunner` counts it in metrics. What doesn't:

- `Ledger` is a stub (`HashMap<Long, Wallet>` + empty `deposit/reserve/release/settle`);
  `Wallet` is an empty record.
- `WalCodec.encodeDeposit` returns `new byte[]{}` and there is no decode tag — a deposit
  can never be written to or read from the WAL (the `DepositRecord` branch in
  `recover()` is unreachable).
- In `writerLoop`, a `DepositCommand` falls into the catch-all `case OrderCommand` TODO:
  its future is **never completed** (a real deposit would hang `MatchingRunner`'s
  `allOf(...).join()` forever), yet the watermark still advances past its offset without
  a WAL record.

No gateway endpoint produces deposits, so none of this is reachable in production — but
treat any change here as "finish the feature", not "fix a bug in passing".

## `:matching-service` — hosting the engine

`MatchingRunner` (start/stop lifecycle, integration-testable; `MatchingService.main()` is
a thin wrapper) runs a hand-written consume loop:

```
poll orders.commands (partition 0) → CommandCodec.decode → orderService.submit(cmd, offset)
  → join all futures → producer.flush() → consumer.commitSync()
```

The consumer `assign`s partition 0 explicitly (single-partition topic assumption), uses
manual commits, `auto.offset.reset=earliest`, and an idempotent producer. On startup it
seeks to `lastSourceOffset() + 1` from the recovered WAL.

Adapters: `FileCommandLog` (the WAL — each record framed as
`[4B length][4B crc32][payload]`; replay stops at the first torn or corrupt frame, which
makes a partially written tail harmless) and `KafkaMarketFeedSink` (events →
`orders.trades`). `InMemoryCommandLog` and `DummyMarketFeedSink` exist for tests.

The runner also owns everything the engine deliberately doesn't: a
`PrometheusMeterRegistry` (common tag `application=matching-service`), the
`exchange.commands.processed` counter (tagged `type=place|cancel|deposit`), JVM-GC and
Kafka-client binders, a bare `com.sun.net.httpserver.HttpServer` serving `/metrics` on
`metricsPort` (9400 in production, `null` in tests to skip the server), and a `/tmp/alive`
heartbeat touch every 5s when a heartbeat path is configured (container liveness).

## `:app` — the gateway

Package root is `com.dawidpawliczek.app`, but source directories under `src/main/kotlin/`
do **not** mirror the package (e.g. `src/main/kotlin/auth/` holds
`com.dawidpawliczek.app.auth`) — don't let the IDE "fix" this silently.

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

## Durability and recovery

Two deliberate durability layers ([ADR-0004](adr/0004-two-durability-layers.md)): the
Kafka log decouples producers/consumers and gives replay to downstream consumers; the
engine's own WAL gives deterministic single-threaded state reconstruction. The Kafka
offset stored in every WAL record stitches them together idempotently.

On startup `OrderService.recover()` replays the WAL in order — re-submitting each place
and re-applying each cancel — rebuilding the book, the id counter, and the source-offset
watermark *before* the writer thread starts. On (re)assignment the consumer seeks to
`lastSourceOffset() + 1`, so commands that were WAL-synced but whose offset commit was
lost in a crash are not re-consumed; the engine additionally drops any command whose
offset is `<= watermark` (idempotent apply), covering re-delivery inside one process
lifetime.

**Known, deliberate gap**: a crash after WAL `sync()` but before `producer.flush()` loses
those events from `orders.trades` — recovery rebuilds the book correctly but never
republishes. Market-data consumers can miss events; the book itself cannot diverge. This
is also why `orders.trades` must not be promoted to a source of truth about money
([ADR-0001](adr/0001-ledger-in-hot-path.md) rejects the settlement-from-topic variant on
exactly this ground).

## Observability

Prometheus scrapes `matching:9400` every 15s (`devops/prometheus.yml`); Grafana renders
an orders/s dashboard, fully file-provisioned under `devops/grafana/` with a **fixed
datasource `uid: prometheus`** — the fixed uid is what makes the dashboard portable;
without it the panel binds to a random per-install datasource uid and breaks on
clone/reset.

Note: `exchange_commands_processed_total` counts commands *ingested* from Kafka, not
applied — a duplicate dropped by the watermark check still increments it.
