# Settlement ledger — design (LED-1)

As of 2026-08-28. Decision document for the LED epic: what the ledger does, which
classes are introduced, how `OrderService` changes, and how it all survives a restart.
Scope: **spot MVP** — margin, liquidations, and the backstop arrive with the perp
(PERP-1 revisits the decisions in this document).

---

## 1. Role and place in the architecture

A ledger of accounts and balances — **not** the order book (`OrderBook`). It answers
"who has how much and can they afford it", while the book answers "who to match the
order with".

Per D6: the ledger lives **on the same `matching-writer` thread** as the book, as a
stage before matching (the RiskEngine → MatchingEngine pattern from exchange-core). One
logical transaction = one WAL record; replay rebuilds ledger and book together, so the
two-systems-without-2PC problem does not exist.

Lifecycle of funds:

```
deposit ──▶ available ──reserve(place)──▶ reserved ──settle(trade)──▶ counterparty's available
                ▲                             │
                └──────release(cancel / reservation surplus)
```

## 2. Decisions

| # | Decision | Resolution | Rationale |
|---|---|---|---|
| L1 | Operation model | **Variant A**: domain operations (`deposit/reserve/release/settle`) mutating account fields; the transfer model (double-entry by construction, TigerBeetle) considered and deferred | The API boundary is identical in both variants, so an A→B migration touches nothing outside `Ledger`'s internals; revision point: PERP-1. The price: invariants are enforced by property-based tests and an online assertion, not by construction |
| L2 | Assets | Enum `Asset { QUOTE, BASE }`; an account = (userId, asset) with `available` + `reserved` fields | One spot instrument needs exactly two legs; generalise to `symbol → Asset` with multiple instruments (D7) |
| L3 | Limit reservation | BUY: `price × qty` QUOTE; SELL: `qty` BASE. The reservation amount is **derivable** from the frozen limit price and the remaining qty — the book itself is the reservation registry, no separate structure | An order in the book holds the remaining qty; invariant I3 ties the two representations together |
| L4 | Market reservation | Market BUY: cost **priced from the current book** at reservation time (walk the asks for qty); the reservation = exactly that cost. This is allowed because reservation and matching happen on the same thread against the same book state — the pricing equals the settlement to the cent. Market SELL: `qty` BASE like limit | `OrderBook.isMatch` accepts any price for market orders, so no price cap exists — pricing from the book is the only tight reservation. An unmatched market remainder is dropped by the book anyway (doesn't rest), so only the executable part is reserved |
| L5 | NSF | An uncovered order is rejected **entirely**, zero state change, **no WAL record**; signal: `OrderRejectedEvent` | A rejection is not a state change — after a crash the command comes back from Kafka (offset past the WAL watermark) and is rejected again, deterministically. Losing the rejection event itself in a crash = the same known gap as losing trades before flush |
| L6 | Price improvement | Settle at the execution price (maker's price), release the difference `(limit − exec) × filled` from the buying taker's reservation immediately on trade | Without this, reservations bloat and I3 breaks |
| L7 | Funding accounts | `DepositCommand` goes through the normal path Kafka → WAL (kind 2) → apply; the source of funds is a **treasury** account (userId 0) seeded by a "mint" on first replay | Seed through the system, not beside it — otherwise replay can't rebuild balances. Treasury keeps the global sum constant (I1 includes treasury) |
| L8 | User seed | The gateway sends a `DepositCommand` after registration (fixed starting amount per asset) | The simplest way to close the loop; real deposits/withdrawals = out of scope |

## 3. Requirements and invariants

Functional: deposit, reservation on place, settlement on trade (with L6), release on
cancel, NSF rejection with an event, balances readable from outside (LED-4: a
projection in the gateway built from `BalanceChanged` events).

Invariants (each has its own test, see §7):

- **I1** — for each asset, `Σ(available + reserved)` over all accounts (treasury
  included) is constant.
- **I2** — `available ≥ 0` and `reserved ≥ 0` always, on every account.
- **I3** — an account's `reserved` = Σ of the derivable reservations of its open
  orders in the book.
- **I4** — state after `recover()` is identical to the pre-crash state
  (replay-equivalence).

Non-functional: mutations exclusively from the `matching-writer` thread (zero locks,
like `OrderBook`); `long` arithmetic with an explicit overflow check on `price × qty`
(`Math.multiplyHigh` / `Math.multiplyExact`); zero dependencies beyond the JDK; account
fields private — **nobody outside `Ledger` mutates balances** (this is the boundary
that makes the A→B migration possible).

## 4. New classes

### `:engine`

| Class | Package | Responsibility |
|---|---|---|
| `Ledger` | `application` (or `domain`) | Sole owner of balances. API: below |
| `Account` | same | `available`, `reserved` (long); package-private mutators, called only by `Ledger` |
| `Asset` | `contracts` (must be on the wire) | `QUOTE`, `BASE`; 1 byte on the wire |
| `NsfException` (checked) *or* a `ReservationResult` result | same | The NSF signal; **must not** escape the batch loop (see §5 — `writerLoop` treats an exception as engine failure) — a result value is preferred over an exception |
| `DepositRecord` | `wire` | WAL record kind 2 |

`Ledger` API (everything called from one thread):

```
void   deposit(long userId, Asset asset, long amount)
boolean reserve(long userId, Asset asset, long amount)      // false = NSF, zero changes
void   release(long userId, Asset asset, long amount)
void   settle(long buyerId, long sellerId, long price, long qty)
        // QUOTE: buyer.reserved -= price*qty → seller.available += price*qty
        // BASE:  seller.reserved -= qty      → buyer.available  += qty
long   available(long userId, Asset asset) / reserved(...)   // reads for the projection
long   totalOf(Asset asset)                                  // counter for the I1 assertion
```

Helper (static, pure): `reservationOf(side, price, qty)` — the shared formula for
reservation and release, so both sides compute identically.

### `:contracts`

| Type | Shape | Wire |
|---|---|---|
| `DepositCommand implements OrderCommand` | `(long userId, Asset asset, long amount)` | extend the existing type-2 layout in `CommandCodec` with the asset byte |
| `OrderRejectedEvent implements MarketEvent` | `(long seq, long timestamp, long userId, RejectReason reason)` | new type in `MarketEventCodec`; `RejectReason { NSF }` as an enum, deliberately future-proof |
| `BalanceChangedEvent implements MarketEvent` | `(long seq, long timestamp, long userId, Asset asset, long available, long reserved)` | full account snapshot after each change — idempotent for the gateway projection |

### WAL — record kind 2 (deposit)

```
[1B kind=2][8B sourceOffset][8B userId][1B asset][8B amount]   = 26 B
```

Kinds 0 (cancel) and 1 (place) unchanged — old journals still read. Unknown kind stays
fail-loud.

## 5. Changes in `OrderService` (and neighbours)

Order of operations in `writerLoop` for one command (WAL phase, before matching):

```
PlaceOrderCommand:
  amount = reservationOf(cmd)            // for market BUY: priced from the book (L4)
  if (!ledger.reserve(userId, asset, amount)):
      job.complete(List.of(OrderRejectedEvent(NSF)))   // no WAL, no matching
  else:
      commandLog.append(encodePlace(order, offset))
      // apply phase (after sync()):
      events = orderBook.submit(order)
      for (TradeEvent t : events):
          ledger.settle(...)             // buyer/seller sides from the incoming order's side
          releasePriceImprovement(...)   // L6, only for a taker-BUY with a limit
      emit BalanceChangedEvent for the affected accounts

CancelOrderCommand:
  order = orderBook.cancel(...)          // ⚠ must return the cancelled order
  ledger.release(order.userId, asset, reservationOf(order))   // the remainder
DepositCommand:
  commandLog.append(encodeDeposit(...)); ledger.deposit(...)
```

Consequences for existing code:

1. **`OrderBook.cancel` must return the cancelled `Order`** (today it returns only a
   `CancelEvent` without qty/price/side — the ledger has nothing to compute the
   release from). Engine-internal change; the `CancelEvent` wire format is unchanged.
2. **Trade side**: `Trade` carries maker and taker but doesn't say who was buying. The
   buyer is determined by the incoming (taker) order's side — settlement is computed
   in `writerLoop`, where the command is at hand. `Trade` stays unchanged on the wire.
3. **NSF is not an exception in the loop** — `writerLoop` catches `Exception` as
   engine failure (kills the logical process); hence `reserve` returns a result. A
   rejected command advances the in-memory watermark (dedup) but does not reach the
   WAL — after a crash it comes back from Kafka and is rejected again (L5).
4. **`recover()` through a shared apply path.** Today recovery calls
   `orderBook.submit` directly; with the ledger, replay must perform the same
   reservations and settlements as the live path. Refactor: one function
   `apply(record) → events`, called from both `writerLoop` and `recover()` — that is
   the real proof of I4, not two parallel implementations.
5. **`:matching-service`**: decoding `DepositCommand` in the consumer loop —
   mechanical, like cancel.
6. **Gateway**: publishes a `DepositCommand` after registration (L8); a
   `BalanceChangedEvent` projection → `GET /account` + WS push (LED-4). The
   projection is a read model — the engine remains the only ledger.

## 6. Scenarios to work out in tests

1. Happy path: deposit ×2 → BUY limit → crossing SELL → both sides' balances.
2. Partial fill: BUY 10 filled 6 — the remainder's reservation of 4 × limit stays;
   then cancel → release of exactly the remainder.
3. Price improvement: BUY limit 105, exec 100 → release 5 × filled on trade.
4. NSF: an order beyond available funds → `OrderRejectedEvent`, ledger state
   byte-for-byte unchanged, the next command in the batch processed normally.
5. Market BUY into a shallow book: reservation = cost of the executable part, the
   remainder dropped by the book with no dangling reservation.
6. Deposit + crash-replay (I4): command sequence → state snapshot → `recover()` on a
   fresh instance from the same WAL → identical states.
7. Self-trade: a user trades with themselves — balances return to the starting point
   (this will genuinely happen: the wash-trading bot in DET).

## 7. Test plan

- Unit tests per operation (`Ledger` in isolation, LED-2).
- **Property-based**: a random sequence of thousands of operations with a fixed seed;
  after every operation assert I1 + I2. This is the future proof for the 31.10 gate.
- Integration in `OrderService` (LED-3): the §6 scenarios, including I3 after each
  place/trade/cancel series and I4 via crash-replay.
- Online (LED-5): an `exchange.ledger.total{asset}` metric + an NSF counter in
  `:matching-service`; an I1 violation = a loud metric. A flat line on Grafana =
  living proof.

## 8. Implementation order

1. **LED-2** — `Ledger` + `Account` + tests (including property-based), zero
   integration.
2. **LED-3a** — refactor `recover()`/`writerLoop` onto a shared `apply` (no ledger
   yet, tests green before and after — a pure refactor).
3. **LED-3b** — `DepositCommand` end-to-end (contracts, `CommandCodec`, `WalCodec`
   kind 2).
4. **LED-3c** — reserve/settle/release in `apply`, `OrderBook.cancel` returns
   `Order`, the §6 scenarios.
5. **LED-4** — `BalanceChanged`/`OrderRejected` events on the wire, the gateway
   projection, deposit on registration.
6. **LED-5** — metrics and the online assertion.

Step 2 is deliberately separate: it is the only refactor of existing behaviour and we
want it done on green tests before any new logic lands.

## 9. Open questions (not blocking the start)

- Does `BalanceChangedEvent` go on `orders.trades` or a separate topic
  (`accounts.events`)? For the MVP: the same topic; decide at LED-4.
- Fees — out of scope; when they arrive, they enter as a third leg in `settle` to a
  system account (an argument for migrating to the transfer model).
- Multi-instrument (D7): `Asset` per symbol and `Map<symbol, OrderBook>` — the ledger
  is ready via the (userId, asset) key; the rest arrives with the perp.
