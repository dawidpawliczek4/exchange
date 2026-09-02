# ADR-0004: Two durability layers — Kafka log + engine WAL

Status: accepted (backfilled 2026-08-28; thesis decision D9). The "known, accepted gap"
consequence below is superseded by
[ADR-0006](0006-recovery-republishes-unpublished-events.md).

## Context

With Kafka on the order path ([ADR-0003](0003-kafka-stays-on-order-path.md)), commands
are already durable in the broker's log before the engine sees them. Keeping a second,
engine-owned WAL looks redundant at first sight: why persist every command twice?

## Decision

Keep both, deliberately, because they answer different questions:

- **Kafka log** — decoupling and replay *for consumers*: any number of downstream
  processes (market data, pipelines, future services) can re-read the streams
  independently of the engine.
- **Engine WAL** (`journal.bin` via `FileCommandLog`) — deterministic single-threaded
  state reconstruction: `recover()` replays it sequentially before the writer starts,
  rebuilding book, id counter, and (per [ADR-0001](0001-ledger-in-hot-path.md)) balances
  with no Kafka dependency at recovery time, in exactly the order the writer originally
  applied.

The two layers are stitched together by storing the Kafka offset of the originating
command in every WAL record:

1. On (re)assignment the consumer seeks to `lastSourceOffset() + 1` — commands that were
   WAL-synced but whose Kafka offset commit was lost in a crash are not re-consumed.
2. The writer drops any command with `sourceOffset <= watermark` (completing its future
   with an empty result) — redelivery within a process lifetime is idempotent.

Rebuilding engine state from the Kafka log alone was rejected: recovery would depend on
broker availability and consumer semantics, replay-to-the-engine would have to be
distinguishable from live traffic, and order-id assignment would not be reproducible
without logging it anyway.

## Consequences

- Every command is persisted twice; the WAL write (plus group-commit fsync) is on the
  hot path and is a deliberate object of the latency study.
- Crash recovery is local and fast: read one file forward, seek the consumer, go.
- Known, accepted gap: a crash after WAL `sync()` but before `producer.flush()` loses
  the corresponding events from `orders.trades` — the book recovers correctly, but those
  events are never republished. Consequence: `orders.trades` is market data, not a
  source of truth (which is precisely why ADR-0001 rejected settlement-from-topic).
- WAL format evolution is engine-internal (tagged-union records, unknown kinds fail
  loud); Kafka wire evolution is a `:contracts` concern. The two formats version
  independently.
