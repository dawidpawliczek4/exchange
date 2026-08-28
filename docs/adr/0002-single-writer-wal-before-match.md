# ADR-0002: Single-writer engine with WAL-before-match

Status: accepted (backfilled 2026-08-28; decision predates the ADR log)

## Context

The engine must be durable (an acknowledged command survives a crash), deterministic on
recovery, and fast. The classic alternatives: lock-based concurrent access to the book
with per-command persistence, sharding the book across threads, or a database as the
system of record.

## Decision

One `matching-writer` thread owns all mutable engine state (book, id counter, watermark —
and, per [ADR-0001](0001-ledger-in-hot-path.md), balances). Callers enqueue commands onto
a bounded `ArrayBlockingQueue` and get a `CompletableFuture` back. The writer drains
batches and, per batch: append every command to the WAL, `sync()` once (group commit),
advance the watermark, and only then match and complete the futures.

One logical transaction = one WAL record, applied by one thread — replay is sequential
re-execution and reproduces the exact state, including assigned order ids (ids are
assigned before logging). This also settles the multi-instrument question in advance
(thesis D7): one writer for *all* instruments (`symbol → OrderBook`), because sharding
per instrument would split the WAL and break cross-instrument ledger atomicity, while a
single thread has throughput headroom to spare (see
[benchmarking.md](../benchmarking.md)).

The LMAX/exchange-core pattern; account model direction per TigerBeetle.

## Consequences

- WAL-before-match is the invariant everything else leans on: a command has no effect
  before it is durable, so recovery can never observe half-applied state.
- Group commit amortises fsync across a batch — the dominant persistence cost scales
  with batch count, not command count. Batching is also visible in latency data (median
  *falls* with load; see benchmarking.md §3).
- Backpressure is explicit: the queue is bounded; `submitOffer` fails fast with
  `EngineOverloadedException` when full (not yet wired into the Kafka runner).
- The writer loop is fail-stop: any exception halts the engine and fails all queued
  futures, rather than continuing with possibly corrupt state.
- No intra-engine parallelism — by design; throughput scaling beyond one core is out of
  scope (thesis "future work": Disruptor-style hot path).
