# Benchmarki JMH — stan, wyniki, wiarygodność

Stan na 2026-08-27. Podsumowanie tego, co zostało zmierzone w `:benchmark` do tej pory,
na ile tym liczbom ufać i co z nich wynika dla pracy. Źródła: kod w
`benchmark/src/jmh/`, dane w `benchmark/results/`, pipeline w `benchmark/analysis/`.

---

## 1. Co istnieje

| Benchmark | Tryb | Co mierzy |
|---|---|---|
| `OrderBookBenchmark.matchBatch` | `Throughput` | koszt operacji na czystym `OrderBook` (bez Springa, Kafki i I/O) w funkcji szerokości arkusza |
| `LatencyBenchmark.run` | `SingleShotTime` (JMH jako powłoka) | rozkład opóźnień w pętli otwartej przy zadanym tempie nadejść: `OrderService` (kolejka + wątek `matching-writer` + WAL w pamięci), sekcja 3 |
| `Wyklad1` | `SingleShotTime`, 100 forków | czas zimnego startu (materiał z wykładu, nie do pracy) |

`Workload.generate(n, spread, seed)` — deterministyczny generator: ceny jednostajne
w `[10000−spread, 10000+spread]`, strona rzutem monetą, ilość 1–10, tylko zlecenia
limit, ustalone ziarno 42.

Konfiguracja `matchBatch`: 10 forków (`-Xms2g -Xmx2g -XX:+AlwaysPreTouch`),
10 iteracji rozgrzewki + 20 pomiarowych po 1 s, 50 000 zleceń na inwokację,
świeży `OrderBook` co inwokację (`Level.Invocation`), `spread ∈ {10, 100, 1000, 10000}`.

Pipeline analizy: `gradlew :benchmark:jmh` → `results.json` (ulotny, w `build/`) →
`jmh_to_csv.py` → CSV z metadanymi środowiska (commit, JDK, flagi, maszyna) w
`benchmark/results/` (commitowany) → `plot_spread.py` → wykresy SVG/PDF w
`benchmark/figures/`.

## 2. Wyniki: degradacja z szerokością arkusza

Przebieg `2026-07-31_spread-sweep.csv` (Apple M4 Pro, macOS, JDK 25, JMH 1.37):

| spread | poziomów cen | ops/s | ns/op | względem spread=10 |
|---|---|---|---|---|
| 10 | 21 | 20 598 935 ± 1,03 % | 48,5 | 100 % |
| 100 | 201 | 15 953 861 ± 1,53 % | 62,7 | 77 % |
| 1000 | 2 001 | 12 053 757 ± 0,38 % | 83,0 | 59 % |
| 10000 | 20 001 | 9 353 060 ± 0,51 % | 106,9 | 45 % |

Interpretacja:

- **Tysiąckrotne poszerzenie arkusza kosztuje tylko 2,2×** — podpis złożoności
  logarytmicznej, zgodny z `TreeMap` pod spodem. Dobra wiadomość i dobry wynik do
  rozdziału o implementacji.
- **Ale koszt na podwojenie liczby poziomów rośnie** (4,3 → 6,1 → 7,2 ns/op na
  podwojenie): krzywa jest wypukła w skali logarytmicznej, czyli czysty log to nie
  wszystko. Trzy mechanizmy poruszają się razem i ten eksperyment ich **nie
  rozdziela**: (a) hierarchia cache — szerszy arkusz to większe drzewo i gorsza
  lokalność; (b) presja na GC — przy szerokim arkuszu prawie nic się nie dopasowuje,
  więc `Level.Invocation` wyrzuca ~50 tys. żywych zleceń na inwokację; (c) zmiana
  proporcji ścieżek kodu — im szerszy arkusz, tym mniej dopasowań, a więcej wstawień.
  Rozdzielenie: `-prof gc` + licznik faktycznych dopasowań (w backlogu).

## 3. Pętla otwarta: opóźnienie w funkcji tempa nadejść

`LatencyBenchmark` to aparat z THESIS.md w pierwszym działającym wcieleniu. Konstrukcja:

- **Pętla otwarta z intended-start-time**: generator strzela według harmonogramu
  `t0 + i·okres` (spin-wait), niezależnie od tego, czy silnik nadąża. Opóźnienie
  liczone od *zamierzonego* startu do skompletowania future — pomiar odporny na
  coordinated omission (Tene), chwilowa czkawka silnika obciąża percentyle wszystkich
  zaległych zleceń, tak jak widziałby to klient.
- **JMH wyłącznie jako powłoka cyklu życia** (`SingleShotTime`): rozgrzewka, forki
  i `@Param` za darmo; wynik JMH (s/op) jest ignorowany, pomiar niesie własny
  `ConcurrentHistogram` (auto-resize; zapis na wątku `matching-writer` w
  `whenComplete`). Iteracja ma stały czas 5 s — liczba zleceń wynika z okresu.
- **Świeży `OrderService` + `InMemoryCommandLog` co iterację** — arkusz nie
  akumuluje głębokości między iteracjami; WAL w pamięci, więc mierzony jest silnik
  z kolejką, *bez* kosztu `fsync` (strategie utrwalania to osobny, przyszły przebieg).
- **Wyjście**: jeden log interwałowy HdrHistogram na iterację pomiarową
  (`results/<data>_<run>/latency-p<okres>-f<pid>-i<NN>.hlog`), scalanie dokładne
  (`add()` histogramów, nie uśrednianie tabel percentyli) w `plot_latency.py`,
  wykres opóźnienie-vs-percentyl w `figures/latency-percentiles.{svg,pdf,png}`.

Przebieg `2026-08-27_latency-mac` (M4 Pro, 3 forki × 5 iteracji, eksploracyjny):

| tempo nadejść | próbki | p50 | p99 | p99,9 | max |
|---|---|---|---|---|---|
| 100/s | 7,5 tys. | 39,5 µs | 87 µs | 949 µs | 3,2 ms |
| 1 tys./s | 75 tys. | 9,8 µs | 37 µs | 1,17 ms | 9,9 ms |
| 10 tys./s | 750 tys. | 7,5 µs | 27 µs | 1,12 ms | 14,5 ms |
| 100 tys./s | 7,5 mln | 6,3 µs | 34 µs | 8,1 ms | 14,5 ms |

Dwie obserwacje, obie gotowe do pracy:

1. **Mediana spada z obciążeniem** (39,5 → 6,3 µs). Przy rzadkim strumieniu każde
   zlecenie płaci pełny park/unpark wątku `matching-writer` (~30–40 µs); przy gęstym
   wątek jest ciągle gorący, a `drainTo` amortyzuje handoff na całą partię. Batching
   single-writera widoczny wprost w danych.
2. **Przy 100 tys./s ogon łamie się w okolicy p99** i skacze do ~8 ms: czkawka
   (GC/scheduler) buduje zaległość w kolejce, którą dziedziczą kolejne zlecenia.
   Pętla zamknięta by tego nie pokazała — to dokładnie artefakt, dla którego aparat
   jest otwarty. Niższe tempa mają podobne maksima, ale rzadziej, stąd rozjazd
   dopiero za p99.

Wiarygodność: te same zastrzeżenia co w sekcji 4 (laptop, throttling, tło) plus
WAL w pamięci — liczby są eksploracyjne, kampania finalna z `@Fork(10)` na
stacjonarnym Linuksie po zamrożeniu kodu.

## 4. Czy te liczby są wiarygodne

**Tak — jako wynik eksploracyjny. Nie — jako liczby do pracy.** Konkretnie:

Co jest solidne:

- **10 forków.** Zmierzone wprost: przy `@Fork(1)` dwa identyczne przebiegi różniły
  się o 3,4 % przy raportowanym ±1,7 %, a samo oszacowanie błędu wahało się 4×.
  Dziesięć forków sprowadziło błąd do ≤1,5 % względnego i ustabilizowało go. Nie obniżać.
- Deterministyczny workload (ziarno 42), stała pamięć z `AlwaysPreTouch`, rozgrzewka
  przed pomiarem, 200 próbek na punkt.
- CSV niesie pełne metadane środowiska — wynik jest interpretowalny bez tego repo.

Co podważa liczby (wszystko znane i zapisane, nic nie jest ukryte):

1. **Laptop.** Powtarzalność między przebiegami ~10 % mimo przedziałów ufności ~1 %:
   ta sama konfiguracja przy spread=10 dała 22,86 mln ops/s w krótkim przebiegu
   i 20,60 mln w 20-minutowym sweepie — throttling termiczny. Przedział ufności JMH
   opisuje precyzję *w obrębie przebiegu*, nie powtarzalność między przebiegami.
   **Finalne liczby do pracy zbieramy na stacjonarnym Linuksie ze stałymi zegarami.**
2. **Proweniencja przebiegu z 31 VII:** CSV deklaruje commit `ead71a4` z
   `git_clean: false` (parametryzacja spread nie była jeszcze zcommitowana), więc
   dokładne odtworzenie *tamtego* przebiegu z historii gita nie jest możliwe.
   Obecny stan repo zawiera już właściwy kod — następny przebieg będzie czysty.
3. **Kształt workloadu jest nierealistyczny:** rozkład jednostajny cen, gdy realne
   arkusze mają ogony potęgowe (Bouchaud i in. 2002). Wniosek jakościowy (degradacja
   ~log) się utrzyma, konkretne liczby — niekoniecznie. Generator gaussowski w backlogu,
   walidacja na LOBSTER w planie.

Pułapki operacyjne (kosztowały już czas, nie wpaść ponownie):

- `build/results/jmh/results.json` **nie jest czyszczony między przebiegami** —
  nieaktualny plik po skasowanym benchmarku wygląda na poprawny. Sprawdzać datę.
- `Wyklad1` **jedzie razem z każdym `:benchmark:jmh`** i ląduje w tym samym JSON-ie
  (100 forków single-shot = długi, bezużyteczny doklejony czas). Do zrobienia:
  `includes = listOf("OrderBookBenchmark")` w bloku `jmh {}`.

## 5. Ustalenia metodyczne (same w sobie materiał do rozdziału 6)

1. **`@Fork(1)` czyni raportowany błąd fikcją** — mierzy tylko rozrzut wewnątrz JVM,
   pomijając wariancję między JVM, która jest większym składnikiem.
2. **Przedział ufności ≠ powtarzalność** — na laptopie różnica między przebiegami
   (~10 %) była o rząd większa niż deklarowany błąd (~1 %). Test powtarzalności
   (dwa niezależne pełne przebiegi) jest obowiązkowy przed cytowaniem liczb.

Obydwa ustalenia są zmierzone, nie zasłyszane — to gotowe akapity do rozdziału
o metodyce jako uzasadnienie decyzji projektowych aparatu.

## 6. Czego te benchmarki NIE mierzą

`matchBatch` to pętla zamknięta na czystym arkuszu: **koszt operacji**, nie opóźnienie.
Rozkład opóźnień przy zadanym tempie nadejść mierzy `LatencyBenchmark` (sekcja 3) —
ale na razie z WAL-em w pamięci, więc bez kosztu trwałości. Podział ról:

| Aparat | Pytanie | Poziom |
|---|---|---|
| `OrderBookBenchmark` | ile kosztuje operacja na arkuszu | mikro, bez I/O |
| `LatencyBenchmark` | jaki rozkład opóźnień przy zadanym λ | silnik + kolejka, WAL w pamięci |
| (przyszły przebieg z `FileCommandLog`) | ile dokłada strategia utrwalania | system, WAL na dysku |

## 7. Backlog pomiarowy (z THESIS.md, sekcja 6)

- [ ] `includes = listOf("OrderBookBenchmark")` w `jmh {}`
- [ ] `-prof gc` + licznik dopasowań — rozbicie wypukłości krzywej z sekcji 2
- [ ] Osobny benchmark `Mode.SampleTime` (percentyle na poziomie mikro)
- [ ] Generator gaussowski (sigma jako `@Param`), generowanie w `@Setup(Level.Trial)`, stałe ziarno
- [ ] Wstępne napełnianie arkusza do zadanej głębokości w `@Setup`
- [ ] `LatencyBenchmark` z `FileCommandLog` — grupowy fsync vs per-rekord vs brak (pomiar 3 z planu)
- [ ] Powtórka całego sweepa na maszynie pomiarowej po zamrożeniu kodu
