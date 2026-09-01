# ADR-0005: NSF verdict at apply — rejected attempts reach the WAL

Status: accepted (2026-08-31; supersedes ledger-design.md decision L5)

## Context

With balances in the hot path ([ADR-0001](0001-ledger-in-hot-path.md)), a
`PlaceOrderCommand` needs a funds check (reserve) before it may touch the book. The
ledger design (L5) originally placed that check *before* the WAL append: an uncovered
order would be rejected with no WAL record at all, on the argument that a rejection is
not a state change.

That ordering has a determinism hole. `processBatch` is two-phase: phase 1 WAL-appends
the whole batch, phase 2 applies it. A check in phase 1 runs against state that does
not yet include earlier commands of the same batch. Batch `[deposit(u, 1000),
buy(u, cost 1000)]`: live, the buy is checked before the deposit is applied and gets
rejected without a WAL record; after a crash the deposit *is* in the WAL, the buy comes
back from Kafka past the watermark — and this time it is accepted. Replay and live
history diverge.

## Decision

Every command is WAL-appended in phase 1, unconditionally — including places that will
fail the funds check. The verdict happens in phase 2 (apply): `reserve` either succeeds
and the order proceeds to the book, or fails and the engine emits a private
`OrderRejected` on `account.events`, with zero state change. This is the same pattern
cancel has always used (a cancel of an unknown order is appended, then REJECTED at
apply).

Recovery replays the WAL through the same `apply` path as live processing, so
reservations, settlements, releases — and rejections — are reproduced identically.
Overflow while pricing a reservation (`price × qty`, or walking the book for a market
buy) is the same deterministic rejection, never an exception: the writer loop treats
any escaping exception as engine failure.

## Consequences

- The WAL is a faithful log of *attempts*; rejected places consume WAL records and
  order ids. Both are deterministic, so replay equivalence holds.
- Accept/reject rules are now part of the WAL format: changing them (new checks,
  different boundaries) changes what replay reconstructs — an incompatible format
  change, handled like any other (wipe the journal volume).
- A rejection event can be lost in the known flush gap (ADR-0004) like any other
  event; the rejection itself is reproduced by replay, so state stays correct.
- The in-memory watermark advances past rejected commands; after a crash they are
  redelivered from Kafka and rejected again — idempotent by determinism rather than by
  dedup.
