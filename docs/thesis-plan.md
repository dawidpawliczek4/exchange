# Engineering thesis plan — exchange simulation platform with heterogeneous agents

> Source of truth for project direction. When planning any change to this repo, check the
> scope layers (§4), the cut order (§5), and the "further work — do NOT build" list
> before proposing new scope. Update this document as decisions change.
> (Translated from the Polish original; the thesis itself is written in Polish.)

As of: 27 August 2026
Defense: February 2027 · Submission: ~January 2027 (to be confirmed) · Time budget: 240–440 h

---

## 1. Topic

**Working title (PL):** Platforma symulacji giełdy z heterogenicznymi agentami — silnik
dopasowujący, księga rozliczeniowa i traderzy oparci na modelach językowych

**(EN):** An exchange simulation platform with heterogeneous agents — matching engine,
settlement ledger, and LLM-based traders

**One-sentence summary:**
> An exchange simulation platform with heterogeneous agents — an algorithmic crowd and
> LLM-based traders — with a settlement ledger, leveraged contracts, a real-time
> interface, and performance measurement of the matching engine.

**Thesis type per II UWr guidelines:** "a program and/or an IT system" (requires: a
working installation, comparison with other solutions, use cases, user and programmer
documentation). The written part for this type is 10–20 pages + attached sources per the
guidelines — confirm with the advisor, since the earlier 55-page assumption likely comes
from MSc requirements.

**What was abandoned and why:**
- *Original plan* — "the impact of WAL persistence strategies on the latency
  distribution" as the central question. Rejected: the result is largely known from the
  literature (group commit, `synchronous_commit`, `appendfsync`), and benchmarks as 70%
  of the thesis are not what I want to do for four months. The measurement apparatus and
  methodology knowledge stay as one chapter.
- *Recommendation from the first research pass* — rewriting the hot path on Disruptor +
  a formal specification as a "theoretical hook" for the competition. Rejected: I'm not
  racing algorithmic olympiad theses; I'm building the project I want to build, and I'll
  submit to the competition if it turns out well.

---

## 2. Context

### System state (August 2026)
- LOB matching engine, price-time priority. JDK 25, Java (`:engine`, pure Java) +
  Kotlin/Spring Boot (services), Gradle multi-module, ~3.4k lines.
- Book: two `TreeMap<Long, Deque<Order>>`. `OrderService` single-writer: bounded
  `BlockingQueue`, one thread drains in batches, WAL with adaptive group commit (one
  `fsync` per batch), ack after persist + match.
- Ports: `CommandLog` (WAL: append/sync/replay), `MarketFeedSink`.
- `:matching-service` on Kafka: poll → decode → submit → join futures → flush → commit
  offsets. The WAL carries Kafka offsets; after restart, replay rebuilds state and the
  consumer seeks to `lastSourceOffset()+1`.
- Spring Boot gateway: REST + JWT + WebSocket market data. POST /order returns 202
  before persistence.
- Docker Compose + Kubernetes, Prometheus + Grafana, e2e on Testcontainers.
- Open-loop measurement apparatus (HdrHistogram, intended-start-time) under construction
  (~400 lines); JMH results for the pure book: 9–20M ops/s depending on the number of
  price levels.

### Flow
```text
bot crowd / LLM agents / HTTP gateway
        → Kafka (orders.commands)
        → matching-service [RiskEngine → MatchingEngine, one thread, WAL]
        → Kafka (orders.trades, market data)
        → WebSocket (frontend) · Scala pipeline (VWAP, manipulation)
```

### Constraints
- Time: ~12 h/week baseline, up to ~22 h/week when engaged → 240–440 h until January.
- Money budget: hardware effectively unconstrained; LLM APIs ~300–800 PLN total
  (~75–200 USD).
- Formalities: topic not yet registered, one willing advisor. Registration via the
  enrollment system (`zapisy.ii.uni.wroc.pl/theses`), acceptance by the Thesis
  Committee, thesis in APD ≥ 7 days before the defense.
- Dean's Competition (WMI): a February 2027 defense falls outside the 2025/26 edition
  window → possible submission to 2026/27. Not a project goal.

---

## 3. Decisions made

| # | Decision | Resolution | Rationale |
|---|---|---|---|
| D1 | Nature of the thesis | System/platform, not a measurement study | I like writing code; the system exists; benchmarks as one chapter, not the spine |
| D2 | The spine | Market simulation with two agent layers: algorithmic crowd + named LLM agents | The crowd provides liquidity and realistic dynamics (millions of orders); LLMs provide narrative and demo. Without the crowd, LLMs would trade into an empty book |
| D3 | Instruments | Spot + **one** leveraged perpetual | Without leverage, LLM P&L collapses into noise around buy-and-hold; leverage stretches the distribution and yields liquidation cascades as an emergent phenomenon |
| D4 | Perp scope | Isolated margin, fixed max leverage (e.g. 10×), mark = EMA of own book's mid, initial/maintenance margin as two percentages, liquidation = market order of the whole position into the book, bad debt → one backstop account | The minimum that produces the effect. No funding (there's no spot of the same asset the perp would be pulled towards — a consequence of the model, state it explicitly), no cross-margin, ADL, or insurance fund |
| D5 | When perp | Only after spot MVP; gate end of October; max 4 weeks with a hard stop | The margin engine is the most unpredictable piece (realistically 60–100 h) |
| D6 | The ledger | Double-entry ledger in the **same single-writer thread** as the book, as a stage before matching (exchange-core RiskEngine → MatchingEngine pattern; account model per TigerBeetle). Reserve on place, settle on trade, release on cancel, reject `NSF` without cover | One logical transaction = one WAL record → replay rebuilds consistent ledger and book state; no two-system problem, no 2PC |
| D7 | Multiple instruments | One single-writer for all instruments, `symbol → OrderBook` | Sharding per instrument splits the WAL and complicates the ledger; one thread's throughput suffices with a large margin |
| D8 | Kafka | **Stays.** Rewriting the hot path without Kafka is out | Kafka is the bus for the crowd, LLMs, frontend, and the Scala pipeline — without it the pipeline doesn't exist. ms/order latency doesn't matter for LLMs or stylized facts. The cost of Kafka on the order path is measured cheaply: in-process `OrderService.submit` vs end-to-end through Kafka (both paths already exist) |
| D9 | Two durability layers | Kafka log + own WAL, described as a conscious decision | Kafka = decoupling and replay for consumers; WAL = deterministic single-threaded state reconstruction; Kafka offsets in the WAL stitch them together idempotently |
| D10 | Gateway ack semantics | Add an "acknowledged after durable" path (a flag), alongside the current 202 | In a thesis about money, the first defense question will be "what if an order disappears between the gateway and the engine" |
| D11 | Bot crowd | One process, virtual threads (JDK 25), types: zero-intelligence, market maker (simplified Avellaneda–Stoikov or fixed spread + inventory), momentum, noise; proportions per ABIDES RMSC03 (1 MM, 100 value, 25 momentum, 5000 noise) as the starting point | A ready-made, citable template; ~5000 agents give a realistic market |
| D12 | Crowd modes | Keep a door open for two modes through the same port: via Kafka (platform/demo) and in-process (experiments at millions of orders/min) | The hexagon gives this almost for free; don't build it now, but design for it |
| D13 | LLM agents | 4–5 models, mostly cheap/mid + one frontier; decisions in discrete rounds every 30–60 s of simulation time; sessions of 150–300 rounds; system prompt cached; structured output with schema validation + economic validation (clip quantities, don't hard-reject); full logging of prompts/responses/model version/temperature/seed | Fits in ~120–180 USD including tests. Prompt and schema pattern: Lopez-Lira 2025 (`llm_trading_sim`) |
| D14 | LLM evaluation | P&L, Sharpe, max drawdown vs buy-and-hold and a random agent; ≥ 5 market seeds per model; mean ± SD | Without repetition a single result is an anecdote (Alpha Arena: 4 of 6 models underwater) |
| D15 | Frontend | React + TypeScript + Vite, TradingView lightweight-charts (Apache 2.0, attribution), Zustand, native WebSocket. Stream: snapshot + deltas with sequence numbers (Binance pattern), server-side coalescing to 10–30 Hz | Standard, proven stack; 35–60 h |
| D16 | Scala pipeline | Scala 3, a simple Kafka consumer (kafka-clients / FS2-Kafka) or Spark Structured Streaming. **Not Flink** (Scala API removed in 2.0). Rules: windowed VWAP, wash trading (self-match / cycles between accounts), spoofing (one-sided depth spike + cancellation right before/after a trade on the other side). A manipulator bot does it on purpose, the pipeline catches it, alert on the frontend | 15–30 h; it's meant to be a demo, not a separate thesis |
| D17 | Measurement chapter | Code frozen → measure once. Throughput and p99.9 with N bots; latency budget in-process vs Kafka; stylized facts on simulation data | The apparatus exists; ~20–30 h |
| D18 | Hardware | Desktop Linux (Ryzen/Intel, 32–64 GB, consumer NVMe). Optionally a cheap enterprise SSD with PLP as a second device for fsync contrast. No bare-metal rental | The M4 Pro laptop is exploration-only (throttling, ~10% spread) |
| D19 | Competition | Not a goal. Submit if it turns out well | I'm building the project I want to build |

---

## 4. Scope — layers and content

### Core (without this there is no thesis)
1. **Settlement ledger** — accounts, balances, double-entry, reserve/settle/release,
   `NSF`, backstop account; on the engine thread, in the WAL.
2. **Agent crowd** — ZI, MM, momentum, noise; virtual threads; configurable proportions
   and frequencies; seeded.
3. **Frontend** — book (depth), candlestick chart, order form, account/positions/P&L,
   bot leaderboard, alert panel.

### Hook
4. **Leveraged perpetual** (scope per D4) — positions, mark price, liquidations, backstop.
5. **LLM agents** — named models, decision rounds, structured output, leaderboard, full log.

### Extras
6. **Scala pipeline** — VWAP + wash + spoofing + manipulator bot + alert.
7. **Measurement chapter** — throughput/p99.9, latency budget, stylized facts.

### Further work (to be written up in the thesis, NOT built)
- Rewriting the hot path on Disruptor / removing Kafka from the order path.
- Cross-margin, multiple perp instruments, funding, ADL, insurance fund.
- Replication / failover.

---

## 5. Cut order (if November shows delays)

1. In-process vs Kafka measurement → keep only throughput/p99.9 on one path.
2. Spoofing → wash trading only.
3. Number of LLM models → 3.
4. Perp: the D5 gate. If it's not stable under the crowd after 4 weeks → cut it, rescue
   "trading" with a simple short via asset borrowing from a lender account, no
   liquidations.
5. Stylized facts → only fat tails + volatility clustering (two figures).

Never cut: the ledger, the crowd, the frontend, at least 3 LLM models with repetitions.

---

## 6. Schedule

| Period | Goal | Deliverable / gate | Hours |
|---|---|---|---|
| **until 30.09** (wk 1–4) | Formalities + spot MVP | Topic registered, plan agreed with advisor. Loop: ZI crowd → engine → **spot ledger** (double-entry, reservation) → rough frontend (book + chart + account). **Gate:** a live spot market with a realistic-looking chart | 80–110 |
| **October** (wk 5–8) | Perpetual (gate D5) | Positions, mark = EMA, liquidation into the book, backstop. The crowd trades the perp too. **Gate (31.10):** liquidations stable under the crowd, no holes in the ledger (sum of balances = constant). If not → cut 4 | 60–100 |
| **November** (wk 9–12) | LLM + pipeline | Wk 9–10: 2 models → 4–5, leaderboard, log. Wk 11: Scala pipeline + manipulator bot + alert. Wk 12: **code freeze (30.11)**. **Budget gate:** > 100 USD before final experiments → cheaper models / sparser rounds | 70–100 |
| **December** (wk 13–16) | Measurements + experiments + start writing | Measurement campaign (once), LLM sessions with ≥ 5 seeds, stylized facts. Figures. Draft of chapters 4–8. **Gate:** measurement chapter and LLM results done before Christmas | 50–80 |
| **January** (wk 17–20) | Text + documentation + submission | Full text, user and programmer documentation, README with reproduction, buffer for advisor comments, APD ≥ 7 days before defense | 40–70 |
| **February** | Defense | Live demo, presentation. Public artifact on GitHub | 15–25 |

Total: 315–485 h — the upper range exceeds the budget, so the cuts in §5 are real, not
theoretical. Rule: measurement methodology written before measuring; code frozen before
the campaign; measure once.

---

## 7. Measurement chapter — exactly what

- **Apparatus:** open-loop generator, intended-start-time, HdrHistogram, preallocated
  arrays, warmup discarded, validation via an injected 100 ms pause visible in p99.9.
  Repeatability: two independent full runs.
- **Measurement 1 — capacity:** engine throughput and p50/p99/p99.9 (ledger + book +
  WAL) as a function of λ, in-process. Reference points: exchange-core (~5M ops/s,
  place ~1 µs, p99.9 ≈ 150 µs at 5M/s), CoinTossX.
- **Measurement 2 — latency budget:** the same generator through Kafka, end-to-end; the
  difference = the cost of Kafka; broken down into gateway / queue / fsync / matching /
  output. One figure.
- **Measurement 3 — durability (condensed from the old plan):** group fsync vs
  per-record vs none, one figure; optionally consumer NVMe vs PLP.
- **Measurement 4 — realism:** stylized facts (Cont 2001) on simulation data:
  kurtosis/tails of returns, autocorrelation of returns ≈ 0, autocorrelation of
  |returns| decays slowly, book shape (Bouchaud 2002). Comparison against a LOBSTER
  sample.
- **Machine:** desktop Linux, performance governor, turbo off, SMT off, engine thread
  pinned to an isolated core, THP off. Generator on disjoint cores; documented.
- **Statistics:** percentiles, not means; bootstrap CIs on percentiles; Kalibera & Jones
  (ISMM 2013) for repetition counts.

---

## 8. LLM experiment protocol

- **Market snapshot in the prompt:** last, mid, spread, book to 5 levels/side, recent
  trades, candles from the last N rounds, position, available margin, P&L, open orders,
  own decision history (window).
- **Output:** JSON per schema `{reasoning, orders:[{side, type, qty, price,
  leverage?}], cancel:[...]}`; schema validation; economic validation (clip to available
  funds; reject only when nonsensical).
- **Cadence:** 30–60 s of simulation time (the clock may be accelerated); session
  150–300 rounds; order of agents within a round randomized, then price-time.
- **Models:** e.g. DeepSeek, Gemini Flash, Claude Haiku, GPT (cheap tier), + one
  frontier (Claude Sonnet / higher GPT tier). Version snapshot recorded.
- **Cost:** ~3k tokens in + ~0.7k out per decision; 5 models × 250 rounds × 8 seeds ≈
  10,000 decisions/model; with system-prompt caching ≈ 150 USD including dev tests.
- **Metrics:** final P&L, Sharpe, max drawdown, liquidation count, % of no-action
  rounds; buy-and-hold and random-agent benchmarks; ≥ 5 seeds; report mean ± SD, test
  against random.
- **Reproducibility:** log of every prompt and response, market seed, temperature, model
  version; educational-simulation disclaimer; check providers' usage policies.

---

## 9. Thesis structure

1. Introduction: goal, motivation (LLMs on markets; Alpha Arena as context), explicit
   own contribution.
2. Review of existing solutions and comparison: ABIDES, Lopez-Lira `llm_trading_sim`
   (closest analogue), CoinTossX, exchange-core, JAX-LOB, dYdX v4 / Hyperliquid (perp
   mechanics). Table + positioning: nobody combines a performant engine with
   measurements + perp + crowd + LLM + pipeline + UI.
3. Requirements and use cases.
4. Architecture: engine, ledger, WAL, Kafka (two durability layers — deliberately),
   gateway, frontend, pipeline.
5. Market mechanics: spot, perpetual, margin, mark price (simplification: EMA of own
   mid), liquidations, backstop.
6. Agents: the crowd (types, parameters, ABIDES), LLM agents (prompt, schema,
   validation, memory).
7. Streaming pipeline and manipulation detection.
8. Evaluation: performance, latency budget, durability, stylized facts, LLM results.
9. User and programmer documentation (requirement of the thesis type).
10. Summary, limitations, further work.

---

## 10. Defense demo (the first 60 seconds)

One screen: a chart and book living off the crowd, a trade counter (millions/h), a
leaderboard of named LLM models with live P&L (someone +23%, someone liquidated), and in
the corner a pipeline alert: "account X: spoofing pattern". Then one slide with the
latency budget and one with stylized facts.

"The one memorable thing": the LLM leaderboard + the counter + the manipulation alert,
all live.

---

## 11. To confirm with the advisor (September)

- Length of the written part (10–20 pages per the "system" type guidelines vs the
  earlier 55-page assumption).
- Peer-reviewed source requirement (the guidelines say "source review"; use
  peer-reviewed where possible regardless).
- Exact APD submission deadline and defense date.
- Whether the advisor is fine with the perp scope from D4 and the cuts from §5.
- Thesis language (Polish) and a possible English abstract.

---

## 12. Key literature (to cite)

**Markets and agents:** Cont (2001) stylized facts; Gode & Sunder (1993)
zero-intelligence; Farmer, Patelli, Zovko (PNAS 2005); Bouchaud et al. (2002) book
shape; Avellaneda & Stoikov (2008) market making; Byrd, Hybinette, Balch — ABIDES
(arXiv:1904.12066); Harris *Trading and Exchanges*.
**LLMs as traders:** Lopez-Lira (2025) "Can Large Language Models Trade?"
(arXiv:2504.10789, `llm_trading_sim`); FinMem (arXiv:2311.13743); TradingAgents
(arXiv:2412.20138); Alpha Arena (nof1.ai, 2025) — non-peer-reviewed, as context.
**Perpetuals:** He, Manela, Ross, von Wachter (arXiv:2212.06888); Ackerer, Hugonnier,
Jermann, *Mathematical Finance* 2025; dYdX v4 and Hyperliquid docs (technical,
non-peer-reviewed).
**Engines and ledgers:** CoinTossX (*SoftwareX* 2021, arXiv:2102.10925); exchange-core
(GitHub); TigerBeetle (double-entry docs, two-phase transfers); Fowler *The LMAX
Architecture*; Thompson et al. — Disruptor.
**Durability:** Kleppmann *DDIA* (ch. 3, 7, 11); Pillai et al. (OSDI 2014); Rebello et
al. (ATC 2020).
**Measurement methodology:** Kalibera & Jones (ISMM 2013); Georges, Buytaert, Eeckhout
(OOPSLA 2007); Gil Tene *How NOT to Measure Latency*; Ousterhout (CACM 2018); Shipilëv
*JVM Anatomy Quarks*.
**Manipulation:** ESMA MAR (spoofing/layering definitions); Do & Putniņš (SSRN 4525036).
**Data:** LOBSTER (NASDAQ samples).

---

## 13. First steps (next two weeks)

1. Advisor meeting: summary sentence + decision table + schedule. Register the topic.
2. Order the desktop (if not already there), Linux, core isolation.
3. Design the ledger model (accounts, transfers, reservation) and its place in the
   pipeline before matching; written to the WAL as part of the same record.
4. First crowd: ZI traders only, via Kafka; check whether the chart "looks like a
   market".
5. Ugly frontend: book + chart + balance. Loop closed.
