# ADR-0006: Recovery republishes events lost before the producer flush — feed topics are ordered prefixes of the event streams

Status: accepted (2026-09-02; partially supersedes [ADR-0004](0004-two-durability-layers.md) — its "known, accepted gap" consequence)

## Context

The writer WAL-syncs a batch, applies it, hands the events to the Kafka producer, and
only then does `MatchingRunner` call `producer.flush()` and commit the consumer offset. A
crash between `sync()` and `flush()` leaves the WAL ahead of `orders.trades` /
`account.events`: recovery rebuilds the exact book and ledger, but the events those
commands produced were never republished. ADR-0004 accepted this because the topics were
fan-out only (WebSocket). A durable projection built on `orders.trades` — candles in
Postgres are the next planned consumer — turns every such hole into a permanent gap in
the projection.

Variants considered:

- **Republish everything on every recovery** and let consumers deduplicate by `seq`. Puts
  an idempotence requirement on every consumer and makes restart cost proportional to
  the journal.
- **Move the flush before the WAL sync.** Makes the topic ahead of the WAL: a crash then
  publishes events for commands that never became durable, which is the worse direction
  (phantom trades).
- **Ask Kafka what the last published event was, and republish only what is missing.**
  Chosen.

## Decision

Before constructing the engine, `MatchingRunner` reads the last record of partition 0 of
`orders.trades` and of `account.events` and takes `seq` from the first eight bytes of the
event header (no decoding). The two `long`s are constructor arguments of `OrderService`,
which stays framework-free. `recover()` no longer discards the result of the shared
`apply` path: every event with `seq > publishedSeq` for its stream is published through
the normal sink, then the runner flushes once before the consume loop starts.

Two streams, two counters, two watermarks: `MarketEvent.seq` is stamped by `OrderBook`,
`AccountEvent.seq` by `Ledger`; they are independent.

For the republished events to be byte-identical to the lost ones, the WAL now records the
command's timestamp: every record gains an 8-byte timestamp after `sourceOffset` (same
tags, no legacy decoding — pre-change journals are wiped, nothing runs in production).
The writer samples its clock once per command at WAL-append time, and every event the
command produces (live or on replay) carries that value.

The decision rests on an invariant that is now load-bearing and must be preserved:

> Each feed topic is an ordered prefix of its event stream. The last record on the
> topic therefore carries the highest `seq` ever published, and everything above it is
> exactly what was lost.

It holds because `KafkaMarketFeedSink` uses a constant key, both topics are declared
with `partitions: 1` (`devops/k8s/base/topics.yaml`; Compose creates them the same way),
the producer is idempotent (per-partition ordering, no gaps under retry), and the engine
publishes in `seq` order from a single thread. Duplicates cannot arise: the gateway and
every other consumer only read the topics, nothing else writes them.

## Consequences

- Restart is self-healing for downstream projections: after a crash the topic converges
  to the same byte sequence the uninterrupted run would have produced. "Deterministic
  state rebuild" becomes "byte-identical event replay".
- **Splitting `orders.trades` or `account.events` into several partitions breaks the
  invariant** — the last record of one partition says nothing about the others, and
  the producer only orders within a partition. Doing so needs a new ADR that replaces
  the watermark read (e.g. per-partition maxima plus a stricter in-flight limit) or
  moves the watermark into the WAL.
- The engine's constructor gained two `long`s and a clock. Tests that recover with an
  in-memory sink now receive the replayed events (watermark 0 = nothing published);
  they assert or drain them.
- `MatchingRunner.start()` now needs the broker before the engine exists; if Kafka is
  unreachable the watermark read fails fast instead of the loop retrying.
- WAL records grew by 8 bytes and journals written before this change do not replay
  (`just compose-reset`).
- ADR-0004's consequence "`orders.trades` is market data, not a source of truth" is
  weakened but not reversed: the topic is now complete, yet the ledger remains the
  authority on money ([ADR-0001](0001-ledger-in-hot-path.md)).
