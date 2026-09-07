# ADR-0007: Candles are computed by TimescaleDB from a raw trades tape — the gateway stores trades, not aggregates

Status: accepted (2026-09-07)

## Context

The first candle projection lived in the gateway's memory: `CandleProjectionService`
accumulated one OHLCV bucket and broadcast it on `/marketdata/candles` when a trade
crossed the boundary. It had the deviations architecture.md recorded — wall-clock bucket
start, no epoch alignment, dropped empty buckets, quote volume under the name `volume`,
`lastSeq` written but never checked — plus two structural problems: state was volatile
(a crash lost the open bucket and the consumer resumed past it), and there was no history,
so the chart filled only from the moment the page opened. The `candles` table in
`V1__init.sql` was never written or read.

Fixing the projection meant answering where candles are defined and where they persist.

Variants considered:

- **Keep the in-memory projection, ack Kafka offsets only at bucket close.** Cheapest fix
  for durability; replay after a crash is bounded by one bucket. Still no history, and
  the candle definition stays in Kotlin, to be duplicated the moment a history query is
  written in SQL.
- **Aggregate in Kotlin, upsert closed buckets into Postgres.** One row per bucket, cheap
  reads by primary key, WebSocket live path unchanged. Two definitions of a candle (the
  Kotlin accumulator and whatever the history query does), and the aggregation logic
  needs its own replay-determinism tests.
- **Raw trades in plain Postgres, candles computed with `GROUP BY` at read time.** One
  definition, but read cost scales with the number of trades in the window, not with the
  number of candles; a 1000-bot crowd makes a one-hour chart a multi-million-row scan.
- **Raw trades in a TimescaleDB hypertable, candles as a continuous aggregate.** One
  definition in SQL, reads scale with candles, the open bucket comes from real-time
  aggregation, and the trades tape doubles as the dataset the thesis analysis needs.
  Chosen.

## Decision

The gateway consumes `orders.trades` with a stable group (`gateway-trades`, `earliest`)
and appends every `TradeEvent` to a `trades` hypertable (`ts TIMESTAMPTZ` from the event
timestamp, `seq`, both order ids, both user ids, `price`, `quantity`; primary key
`(ts, seq)`). Inserts are `ON CONFLICT DO NOTHING`; the Kafka offset is committed after
the batch is written. At-least-once delivery plus an idempotent insert gives an
effectively-once tape — the same sink-side dedup ADR-0006 assumes downstream projections
will do.

Candles are the continuous aggregate `candles_5s` over `time_bucket('5 seconds', ts)`
with `first(price, seq)` / `last(price, seq)` for open/close, `sum(quantity)` as `volume`,
`sum(quantity * price)` as `quote_volume`, `count(*)` as `trade_count`, real-time
aggregation enabled so the bucket still being written is visible without waiting for the
refresh policy. The policy refreshes from the beginning of the tape (`start_offset` NULL)
so a replay of old trades after downtime is materialized on the next run instead of
falling below the watermark forever. Nothing else defines a candle; the gateway holds no
candle state.

History is served by `GET /marketdata/candles?from&to` (epoch millis). The candle
WebSocket is removed. The chart loads history over REST and extends the last bar from
the existing raw `/marketdata` feed, using the same epoch-aligned bucket rule.

Only the 5-second aggregate exists; hierarchical aggregates, retention and compression are
not introduced until something needs them.

## Consequences

- The candle is defined once, in SQL, and every consumer — chart history, the live bar,
  later the LLM traders' prompts and the thesis analysis — reads the same numbers.
- Replay determinism and crash durability stop being gateway concerns: the tape is
  idempotent by `seq`, the aggregate is a pure function of the tape.
- The trades tape is the raw dataset for the thesis; no separate Kafka dump is needed.
- **Postgres becomes TimescaleDB everywhere**: Compose, the k8s StatefulSet and both
  Testcontainers images must use the same `timescale/timescaledb` tag, and it must not be
  the `-oss` build (continuous aggregates are under the Timescale Community license,
  free to self-host, absent from the Apache-only image). Existing `pgdata` volumes are
  wiped (`just compose-reset`).
- The schema stays a single `V1__init.sql`, now run outside a transaction
  (`executeInTransaction=false`), because creating a continuous aggregate cannot happen
  inside one. Nothing is deployed, so there is no migration history to preserve; a
  half-applied `V1` is fixed by wiping the volume, not by a repair migration.
- The gateway gains a second data-access style (`JdbcTemplate`/`JdbcClient` beside JPA):
  a continuous aggregate is a view, and mapping it as an entity would fight
  `ddl-auto: validate`.
- The live path moves to the client: the frontend owns the open-bar update rule and must
  keep it aligned with `time_bucket`. A drifted rule shows up as a last bar that
  disagrees with history on the next reload — visible, not silent.
- Read cost of the open bucket grows with trades per bucket (real-time aggregation
  scans the unmaterialized tail); at 5-second buckets that is bounded and small.
- This is a deliberate step beyond `docs/thesis-plan.md`'s stack list; it stays within
  its scope (a chart that looks like a market, candles for the LLM prompt).
