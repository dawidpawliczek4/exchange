# ADR-0003: Kafka stays on the order path

Status: accepted (backfilled 2026-08-28; thesis decision D8)

## Context

Every order flows `gateway → orders.commands → matching-service → orders.trades →
consumers`. A broker on the hot path adds milliseconds of latency per order, and
low-latency exchange designs (LMAX, exchange-core) keep the matching path in-process.
Rewriting the hot path without Kafka was considered for the thesis and rejected.

## Decision

Kafka stays. It is the bus that everything else plugs into: the algorithmic agent crowd,
the LLM traders, the HTTP gateway, the frontend feed, and the streaming
(manipulation-detection) pipeline are all just producers/consumers on
`orders.commands` / `orders.trades`. Without it, that fan-in/fan-out doesn't exist.

Per-order latency in the milliseconds does not matter for LLM agents (decision cadence
~30–60s) or for reproducing stylized facts. Where latency *is* the question, the cost of
Kafka is measured cheaply instead of eliminated expensively: the same generator drives
`OrderService.submit` in-process and end-to-end through Kafka — both paths already exist
— and the difference is the Kafka cost (thesis "latency budget" measurement).

## Consequences

- The platform keeps one integration surface: adding an agent type or a consumer is
  "speak the codec on a topic", no engine changes.
- The engine stays testable and benchmarkable in-process; `:matching-service` is a thin
  host, and the crowd can later run in two modes through the same port (via Kafka for
  the platform/demo, in-process for million-order experiments — thesis D12).
- The order path inherits Kafka's delivery semantics; the WAL↔offset reconciliation in
  [ADR-0004](0004-two-durability-layers.md) is what makes redelivery safe.
- A Disruptor-style no-Kafka hot path remains documented future work, not part of the
  thesis scope.
