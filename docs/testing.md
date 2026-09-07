# Testing

How tests are written in this repo: what lives where, the conventions each layer
follows, and where a new test belongs. `./gradlew test` runs everything; the
Testcontainers layers need a running Docker daemon. CI runs the same suite via
`./gradlew build`.

## The layers

| Layer               | Where                                                                                | Tools                                                                 | Speed                   |
|---------------------|--------------------------------------------------------------------------------------|-----------------------------------------------------------------------|-------------------------|
| Pure unit           | `:contracts`, `:engine` (`OrderBookTest`, `LedgerTest`, `WalCodecTest`, codec tests) | JUnit 5 only                                                          | ms                      |
| Engine service      | `:engine` (`OrderServiceTest`)                                                       | JUnit 5 + hand-written doubles                                        | ms (real writer thread) |
| Adapter             | `:matching-service` (`FileCommandLogTest`)                                           | JUnit 5 + `@TempDir`                                                  | ms (real file I/O)      |
| Service integration | `:matching-service` (`MatchingRunnerIntegrationTest`)                                | Testcontainers Kafka                                                  | ~10s+                   |
| Gateway slice       | `:app` (controller tests, `CandleHistoryTest`)                                       | `@SpringBootTest` + Testcontainers TimescaleDB + `@MockitoBean`       | seconds                 |
| End-to-end          | `:e2e` (`TradeFlowE2eTest`)                                                          | Testcontainers Kafka + TimescaleDB, gateway booted, runner in-process | ~30s+                   |
| Frontend            | `:frontend` (test file beside the module, e.g. `src/marketdata/candles.test.ts`)     | vitest + jsdom                                                        | ms                      |

### Pure unit — contracts, the book, the ledger

Codec tests assert **round-trips** (`decode(encode(x)) == x`) plus a fail-loud case for
an unknown type/kind byte. The book and the ledger have no clock — the timestamp is an
argument of `submit`/`cancel`/`deposit` — so `OrderBookTest` passes `0` everywhere and
asserts **whole event lists by equality** —
`assertEquals(List.of(new TradeEvent(...)), ob.submit(order, 0))` — rather than picking
fields out. With timestamp 0, `seq` starting at 1, and explicit order ids, the expected
list is exact. `LedgerTest` follows the same conventions (timestamp 0, exact event
equality) and covers the deposit boundaries (zero, negative, and
balance overflow all yield `DepositRejected` with the balance untouched) plus the
reserve/release/settle mechanics in isolation: reserve failures leave state unchanged,
settle credits only the receiving legs, a self-trade nets to the starting point.

### Engine service — behavioral probing, hand-written doubles

`OrderServiceTest` runs the real single-writer thread against test doubles:
`RecordingCommandLog` (in-memory `CommandLog` that records appends, supports `size()`
and `copy()`) and recording sinks (`RecordingMarketFeedSink` / `RecordingAccountFeedSink`
with a `drain()` that returns-and-clears). The futures are `CompletableFuture<Void>` —
pure completion barriers — so all event assertions read the sinks, i.e. the same channel
production uses. No mocking framework in the engine.

The `Batched` nested class bypasses the queue and calls `processBatch` directly with
hand-built `Job`s, covering the mixed multi-command batches (place+cancel+deposit in one
drain) that `.join()`-per-command submission can never produce.

The distinctive convention is the **probe pattern**: internal state (book contents,
watermark, id counter) is never inspected directly. Instead the test submits a *probe
order* that would cross with the state under question and asserts on the resulting
trades — e.g. "after cancel, a crossing buy trades nothing" proves the cancel removed
the resting order. The ledger analogue: deposit `Long.MAX_VALUE`, recover, then probe
with a further deposit — `DepositRejected` (overflow) proves the balance survived.
Recovery tests build state in one `OrderService`, `close()` it, open
a second one on the same log, and probe the recovered instance. The 3-arg constructor
means "nothing published yet" (both watermarks 0), so the recovered instance
**republishes every replayed event** into the test sink — tests assert that list (it is
the recovery contract of ADR-0006) or `drain()` it before probing; the 6-arg constructor
takes a clock and the two watermarks for the partial-loss cases. Determinism is asserted
by recovering two services from `log.copy()` and comparing replayed events and full
probe sequences; byte-identical replay is asserted by writing with a ticking clock,
recovering with a constant one, and comparing the event lists including timestamps.

Async is handled by `submit(...).join()` — the future completes only after WAL append +
match, so no sleeps or timeouts are needed at this layer.

Since the ledger gates every place, **tests seed balances first**: the `seed(service,
offset, userIds...)` helper deposits generous QUOTE+BASE per user at offsets `0..9`, and
orders start at offset 10 by convention. Balance assertions read
`service.ledger().cashOf/assetOf(...)` directly — sanctioned reads (the future gateway
projection uses the same API), not probe substitutes: settlement scenarios assert exact
arithmetic (`SEED - 500` after buying 5 @ 100, price improvement refunded, remainder
released on cancel), while book contents are still verified by probes.

### Adapter — real I/O, injected corruption

`FileCommandLogTest` writes a real journal into a JUnit `@TempDir` and then damages it
deliberately with raw `FileChannel` writes: flip a payload byte (CRC mismatch), truncate
mid-frame (torn write). Assertions pin the documented replay contract: stop at the first
corrupt or incomplete frame, keep everything before it.

### Service integration — real Kafka, simulated crashes

`MatchingRunnerIntegrationTest` spins up a real broker (`@Testcontainers` +
`KafkaContainer`), creates the topics through `Admin`, and drives a real `MatchingRunner`
with a real journal. Its signature move is simulating a **lost offset commit**: run the
runner, then `alterConsumerGroupOffsets` back to 0 behind its back, restart the runner on
the same journal, and assert (by reading all of `orders.trades`) that no duplicate trades
were published — the WAL watermark test at system level. Trade tests prepend seed
deposits (`seeds()`); an NSF case asserts the `OrderRejected` lands on `account.events`
keyed by the user and no trade is published.

The second crash it simulates is the one ADR-0006 closes — **WAL synced, producer never
flushed**: after a first runner has published normally, the test opens an `OrderService`
on the *same journal* with sinks that only record (a producer that died before
`flush()`), submits crossing orders plus an NSF place at the Kafka offsets those commands
occupy, closes it, then restarts a real runner. The assertion is that the topics now
carry exactly the recorded-but-never-sent events, byte-for-byte (same `seq`, same
timestamps), appended after what the first runner published, with nothing duplicated.

Waiting is a hand-rolled `awaitUntil(description) { condition }` polling helper with a
deadline — assert on *observable progress* (committed offset, `lastSourceOffset()`),
never on sleeps.

### Gateway slice — Spring context, mocked Kafka edge

`:app` tests boot the full Spring context (`@SpringBootTest(RANDOM_PORT)` +
`RestTestClient`) with:

- **Postgres real** — the `timescale/timescaledb` image, via Testcontainers
  `@ServiceConnection` (`TestcontainersConfiguration` is `@Import`ed per test class;
  the image is declared `asCompatibleSubstituteFor("postgres")`);
- **Kafka absent** — `@MockitoBean OrderCommandPublisher` replaces the producer, and
  tests `verify(...)` the exact command published. Mockito appears *only* at this Spring
  boundary, nowhere else in the repo;
- profile `test` (`application-test.yml`);
- `@BeforeEach` cleanup by deleting repositories (sessions → credentials → users, FK
  order); `CandleHistoryTest` clears the `trades` hypertable with `JdbcTemplate` instead,
  there being no JPA entity for it.

`CandleHistoryTest` is the slice for the trades tape: it calls the `RecordTrades` port
directly with hand-built `TradeEvent`s at chosen timestamps (no Kafka), forces
`refresh_continuous_aggregate` so the assertion does not depend on the refresh policy's
timing, and reads `GET /marketdata/candles` back — pinning the aggregate's OHLCV
arithmetic, epoch-aligned bucket starts, the range filter, idempotence under redelivery
and the 400 `ProblemDetail` for an inverted range.

Auth tests drive the real filter chain: `ProbeController` (test-source-only
`/test/protected` endpoint) exists to assert the JWT filter end-to-end. Tokens for
arbitrary user ids are minted directly with the autowired `JwtService`. The convention
covers the full matrix per endpoint: authenticated happy path, invalid body (400, no
publish), missing token (403, no publish).

Kotlin tests use backtick names describing behavior
(`` `logout - refresh token cannot be reused after logout` ``); Java tests use plain
camelCase.

### End-to-end

`TradeFlowE2eTest` boots the gateway as a Spring test with real Kafka + Postgres
containers and runs `MatchingRunner` **in-process** (`.use { }` for lifecycle) — no
Docker images of our own services are involved. Because the gateway has no deposit
endpoint yet, `seedFunds(userId)` produces `DepositCommand`s straight to
`orders.commands` with a raw producer — single-partition ordering guarantees they apply
before the orders. Flow: register over HTTP → JWT → seed → open
the `/marketdata` WebSocket → post crossing orders → assert the decoded event arrives on
the socket → poll `GET /marketdata/candles` until the trade's bucket appears (the one
place the real-time aggregation path is exercised end to end). `ContainerTestUtils.waitForAssignment` gates the race between listener
startup and the first order; a bounded `messages.poll(30s)` replaces sleeps. Negative
path ("cancelled order must not trade") uses a short bounded poll asserting *nothing*
arrives.

### Frontend — pure modules, vitest

`./gradlew build` runs the UI checks too: `:frontend:check` depends on `pnpm test:unit`
and `pnpm check` (oxlint, eslint, `oxfmt --check`), and `:frontend:assemble` on
`pnpm build`, which type-checks with `vue-tsc`. A red vitest or a formatting violation
under `frontend/src/` fails CI exactly like a Kotlin one.

`lightweight-charts` needs a real canvas and throws under jsdom, so anything worth
asserting lives in plain `.ts` modules and the `.vue` component keeps only wiring.
Test files live **next to the file under test, not in a `__tests__/` directory** — the
test for `src/marketdata/candles.ts` is `src/marketdata/candles.test.ts`, the test for
`src/auth/AuthLogin.vue` is `src/auth/AuthLogin.test.ts`. Vitest's default include pattern
picks up both `.spec.ts` and `.test.ts`, but this repo uses `.test.ts` throughout —
rename anything a scaffold generates as `.spec.ts`.

`src/marketdata/candles.test.ts` covers the WebSocket-to-chart conversions that
are easy to get silently wrong: milliseconds → `UTCTimestamp` seconds, flooring rather
than rounding, dropping a bucket that closed with null OHLC, and the monotonic-time guard
that keeps `series.update()` from throwing on an out-of-order frame after a reconnect —
including the fact that the guard's watermark must not advance on a rejected bucket.

The auth side follows the same split. `src/shared/apiError.test.ts` pins the
`ProblemDetail` decoding (`detail` before `title` before a status fallback, the first
validation message per field wins, no response means a network error) and
`src/auth/validation.test.ts` pins the zod schemas against the gateway's rules. Mounting
is fine here — no canvas involved — so `src/auth/AuthLogin.test.ts` mounts the real
component with the store and router mocked (`vi.mock('@/auth/store')`,
`vi.mock('@/router')`) and asserts the four outcomes of a submit: local validation blocks
the call, success navigates to `/`, a `message`-only error renders as the form error, and
server field errors render under their inputs with no form-level message.

## Where does a new test go?

- Byte layout / wire change → codec test in `:contracts` (round-trip + unknown-tag).
- Matching semantics → `OrderBookTest`, timestamp 0, exact event lists.
- Single-writer semantics (WAL ordering, watermark, recovery, ledger) →
  `OrderServiceTest` with `RecordingCommandLog` + probes.
- A `CommandLog`/sink implementation → adapter test next to it, real I/O, `@TempDir`.
- Kafka loop semantics (offsets, commits, redelivery) → `MatchingRunnerIntegrationTest`.
- HTTP contract, auth, validation → `:app` slice test with mocked publisher.
- A full user-visible flow → `:e2e`, only when the flow genuinely spans gateway + engine
  + Kafka; prefer the lower layers otherwise.
- Frontend logic → vitest in a file beside the module, never in a `__tests__/` folder
  (`candles.ts` → `candles.test.ts`, `AuthLogin.vue` → `AuthLogin.test.ts`). Keep the
  logic worth testing in plain `.ts` modules rather than in `.vue` components: mounting a
  component that creates a chart fails under jsdom, which has no canvas.

## Known gaps (backlog)

- **Byte layouts aren't pinned.** Round-trips pass even if a layout changes on both
  sides at once; golden-bytes fixtures (exact expected `byte[]` for known values) would
  protect wire/journal compatibility — including the "WAL kind 1 is byte-identical to
  the old versionless record" claim, which currently has no test.
- `submitOffer` / `EngineOverloadedException` (queue-full) and the writer-loop
  fail-stop path (append throws → all futures exceptional) are untested.
- No property-based tests yet; `ledger-design.md` §7 commits to them (jqwik). With
  implicit reservations the oracle is cross-system conservation — `Σ ledger balances +
  Σ (remaining × limit) over open orders == Σ accepted deposits`, per asset, after every
  operation of a random command sequence — plus no-negative-balance and
  replay-equivalence; the book could get no-crossed-book properties from the same setup.
- The WebSocket JSON shape (Jackson serialization of `TradeEvent`/`CancelEvent`) is
  asserted only by decoding it back in `:e2e`. The frontend now consumes it, so the field
  names *are* a contract and deserve a pinning test — the same goes for the `Candle`
  rows from `GET /marketdata/candles`, which `CandleHistoryTest` deserializes back into
  the same Kotlin class, so a rename on the gateway side passes while the chart breaks.
- The client-side bar rule (`applyTrade`) and the SQL bucket rule are tested separately;
  nothing asserts they agree. A cross-check would replay one trade list through both and
  compare.
- Frontend coverage stops at the pure mapping layer. Nothing exercises the WebSocket
  lifecycle (reconnect, `disposed` guard) or the chart wiring — that needs either a fake
  `WebSocket` or a browser-mode runner, since `lightweight-charts` needs a real canvas and
  throws under jsdom.
