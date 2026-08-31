# Testing

How tests are written in this repo: what lives where, the conventions each layer
follows, and where a new test belongs. `./gradlew test` runs everything; the
Testcontainers layers need a running Docker daemon. CI runs the same suite via
`./gradlew build`.

## The layers

| Layer | Where | Tools | Speed |
|---|---|---|---|
| Pure unit | `:contracts`, `:engine` (`OrderBookTest`, `LedgerTest`, `WalCodecTest`, codec tests) | JUnit 5 only | ms |
| Engine service | `:engine` (`OrderServiceTest`) | JUnit 5 + hand-written doubles | ms (real writer thread) |
| Adapter | `:matching-service` (`FileCommandLogTest`) | JUnit 5 + `@TempDir` | ms (real file I/O) |
| Service integration | `:matching-service` (`MatchingRunnerIntegrationTest`) | Testcontainers Kafka | ~10s+ |
| Gateway slice | `:app` (controller tests) | `@SpringBootTest` + Testcontainers Postgres + `@MockitoBean` | seconds |
| End-to-end | `:e2e` (`TradeFlowE2eTest`) | Testcontainers Kafka + Postgres, gateway booted, runner in-process | ~30s+ |

### Pure unit — contracts, the book, the ledger

Codec tests assert **round-trips** (`decode(encode(x)) == x`) plus a fail-loud case for
an unknown type/kind byte. `OrderBookTest` injects a **deterministic clock**
(`new OrderBook(() -> 0L)`) so events are fully predictable, then asserts **whole event
lists by equality** — `assertEquals(List.of(new TradeEvent(...)), ob.submit(order))` —
rather than picking fields out. With a fixed clock, `seq` starting at 1, and explicit
order ids, the expected list is exact. `LedgerTest` follows the same conventions (fixed
clock, exact event equality) and covers the deposit boundaries: zero, negative, and
balance overflow all yield `DepositRejected` with the balance untouched.

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
a second one on the same log, and probe the recovered instance. Determinism is asserted
by recovering two services from `log.copy()` and comparing full probe sequences.

Async is handled by `submit(...).join()` — the future completes only after WAL append +
match, so no sleeps or timeouts are needed at this layer.

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
were published — the WAL watermark test at system level.

Waiting is a hand-rolled `awaitUntil(description) { condition }` polling helper with a
deadline — assert on *observable progress* (committed offset, `lastSourceOffset()`),
never on sleeps.

### Gateway slice — Spring context, mocked Kafka edge

`:app` tests boot the full Spring context (`@SpringBootTest(RANDOM_PORT)` +
`RestTestClient`) with:

- **Postgres real**, via Testcontainers `@ServiceConnection`
  (`TestcontainersConfiguration` is `@Import`ed per test class);
- **Kafka absent** — `@MockitoBean OrderCommandPublisher` replaces the producer, and
  tests `verify(...)` the exact command published. Mockito appears *only* at this Spring
  boundary, nowhere else in the repo;
- profile `test` (`application-test.yml`);
- `@BeforeEach` cleanup by deleting repositories (sessions → credentials → users, FK
  order).

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
Docker images of our own services are involved. Flow: register over HTTP → JWT → open
the `/marketdata` WebSocket → post crossing orders → assert the decoded event arrives on
the socket. `ContainerTestUtils.waitForAssignment` gates the race between listener
startup and the first order; a bounded `messages.poll(30s)` replaces sleeps. Negative
path ("cancelled order must not trade") uses a short bounded poll asserting *nothing*
arrives.

## Where does a new test go?

- Byte layout / wire change → codec test in `:contracts` (round-trip + unknown-tag).
- Matching semantics → `OrderBookTest`, fixed clock, exact event lists.
- Single-writer semantics (WAL ordering, watermark, recovery, ledger) →
  `OrderServiceTest` with `RecordingCommandLog` + probes.
- A `CommandLog`/sink implementation → adapter test next to it, real I/O, `@TempDir`.
- Kafka loop semantics (offsets, commits, redelivery) → `MatchingRunnerIntegrationTest`.
- HTTP contract, auth, validation → `:app` slice test with mocked publisher.
- A full user-visible flow → `:e2e`, only when the flow genuinely spans gateway + engine
  + Kafka; prefer the lower layers otherwise.

## Known gaps (backlog)

- **Byte layouts aren't pinned.** Round-trips pass even if a layout changes on both
  sides at once; golden-bytes fixtures (exact expected `byte[]` for known values) would
  protect wire/journal compatibility — including the "WAL kind 1 is byte-identical to
  the old versionless record" claim, which currently has no test.
- `submitOffer` / `EngineOverloadedException` (queue-full) and the writer-loop
  fail-stop path (append throws → all futures exceptional) are untested.
- No property-based tests yet; `ledger-design.md` §7 commits to them (jqwik) for the
  ledger invariants I1–I4 — the book could get conservation/no-crossed-book properties
  from the same setup.
- The WebSocket JSON shape (Jackson serialization of `TradeEvent`/`CancelEvent`) is
  asserted only by decoding it back in `:e2e`; once the frontend exists, the exact JSON
  field names become a contract and deserve a pinning test.
