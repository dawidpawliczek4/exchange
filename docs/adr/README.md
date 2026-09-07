# Architecture Decision Records

Numbered, immutable records of load-bearing decisions. A superseded decision gets a new
ADR pointing back, not an edit. Records marked *backfilled* document decisions made
before the log existed.

| ADR                                                     | Decision                                                                                          | Status                                                                |
|---------------------------------------------------------|---------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| [0001](0001-ledger-in-hot-path.md)                      | Ledger (balances) in the engine hot path                                                          | accepted                                                              |
| [0002](0002-single-writer-wal-before-match.md)          | Single-writer engine with WAL-before-match                                                        | accepted (backfilled)                                                 |
| [0003](0003-kafka-stays-on-order-path.md)               | Kafka stays on the order path                                                                     | accepted (backfilled)                                                 |
| [0004](0004-two-durability-layers.md)                   | Two durability layers: Kafka log + engine WAL                                                     | accepted (backfilled); "known gap" consequence superseded by ADR-0006 |
| [0005](0005-nsf-verdict-at-apply.md)                    | NSF verdict at apply — rejected attempts reach the WAL                                            | accepted                                                              |
| [0006](0006-recovery-republishes-unpublished-events.md) | Recovery republishes events lost before the producer flush; feed topics are ordered prefixes      | accepted                                                              |
| [0007](0007-candles-from-trades-tape-in-timescaledb.md) | Candles computed by TimescaleDB from a raw trades tape; the gateway stores trades, not aggregates | accepted                                                              |

## Template

```markdown
# ADR-NNNN: <decision as a statement>

Status: proposed | accepted | superseded by ADR-MMMM · Date: YYYY-MM-DD

## Context
What forces are at play; what variants were considered.

## Decision
What was chosen, stated actively.

## Consequences
What becomes easier, what becomes harder, what is deliberately given up.
```
