# ADR-0001: Ledger (balances) in the engine hot path

Status: accepted · Date: 2026-08-28

## Context

The system has no notion of a balance — any user can place any order and the engine will
match it. The ledger must do two things: a risk check before matching (does the user have
funds; reservation) and settlement after a trade (moving cash/asset between accounts).

Variants considered:

1. **A separate service consuming `orders.trades`** — pure after-the-fact settlement, no
   risk check.
2. **Reservation in the gateway before publishing the command** — a hold in Postgres,
   only then publish to Kafka.
3. **Balances in engine memory, on the `matching-writer` thread** — check and
   reservation right before `submit()`, deposit as a new WAL record kind, recovery
   rebuilds balances together with the book.

## Decision

Variant 3. Balances per (userId, asset) held in the single-writer's state, protected by
the same WAL as orders. The LMAX pattern — this is how exchanges that are simultaneously
broker and custodian (crypto/retail) do it.

## Why not 1 and 2

- Variant 2: a dual write (hold in the DB vs publish to Kafka — a partial failure leaves
  an inconsistency, requiring an outbox) plus a race between reservation and matching,
  because the hold and the match are done by two different processes. Fixable, but at a
  complexity cost out of proportion to the benefit.
- Variant 1: enforces nothing (balances can go negative) and promotes `orders.trades` to
  the source of truth about money, which the topic's current guarantees cannot carry — a
  crash after WAL sync but before `producer.flush()` loses trades without republication,
  so the ledger diverges from the book permanently. Would be thrown away anyway once
  variant 3 lands.

## Consequences

- The risk check is a map lookup on the matching thread — negligible cost, zero I/O in
  the hot path; in exchange it adds another measurement point for the latency study.
- The engine stops being pure matching — `OrderService` gains balance state,
  reservations (hold on place, release on cancel and on fills below the limit), and
  rejection of uncovered orders.
- The WAL gets a new record kind (deposit); the format is a tagged union, so old
  journals replay unchanged, but downgrading the engine version requires wiping the
  journal.
- The known loss gap on `orders.trades` remains cosmetic (market data only) — balances
  recover from the WAL, not from the topic.
- A durable, auditable ledger in a database (deposits/withdrawals/history) is a
  separate, future downstream layer — this ADR does not replace it.
