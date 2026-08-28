# Docs

- [architecture.md](architecture.md) — modules, order path, wire formats (Kafka codecs +
  WAL), the single-writer engine, durability/recovery, observability.
- [operations.md](operations.md) — running and deploying: local dev, Compose, kind, GKE
  (Terraform), state resets. The `justfile` is the canonical entry point.
- [benchmarking.md](benchmarking.md) — what has been measured, the results, how much to
  trust them, methodological findings, measurement backlog.
- [ledger-design.md](ledger-design.md) — design for the settlement ledger (LED epic):
  decisions L1–L8, invariants, new classes, `OrderService` changes, implementation
  order.
- [thesis-plan.md](thesis-plan.md) — the engineering-thesis plan this repo serves:
  scope, decisions D1–D19, schedule, cut order. Check it before proposing new scope.
- [adr/](adr/README.md) — architecture decision records.

Script usage for the benchmark analysis pipeline lives next to the scripts:
[benchmark/analysis/README.md](../benchmark/analysis/README.md).
