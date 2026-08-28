# ADR-0001: Ledger (salda) w hot-path silnika

Status: zaakceptowany · Data: 2026-08-28

## Kontekst

System nie ma pojęcia salda — każdy user może wystawić dowolne zlecenie i engine je
zmatchuje. Ledger musi robić dwie rzeczy: risk check przed matchingiem (czy user ma
środki, rezerwacja) i settlement po trade (przeksięgowanie cash/aktywo).

Rozważane warianty:

1. **Osobny serwis-konsument `orders.trades`** — czysty settlement po fakcie, bez
   risk checku.
2. **Rezerwacja w gatewayu przed publikacją komendy** — hold w Postgresie, dopiero
   potem publish na Kafkę.
3. **Salda w pamięci silnika, w wątku `matching-writer`** — check i rezerwacja tuż
   przed `submit()`, depozyt jako nowy typ rekordu WAL, recovery odtwarza salda
   razem z księgą.

## Decyzja

Wariant 3. Salda per (userId, aktywo) trzymane w stanie single-writera, chronione tym
samym WAL-em co zlecenia. Wzorzec LMAX — tak robią to giełdy będące jednocześnie
brokerem i depozytariuszem (krypto/retail).

## Dlaczego nie 1 i 2

- Wariant 2: dual-write (hold w DB vs publish na Kafkę — częściowa awaria zostawia
  niespójność, wymaga outboxa) plus wyścig między rezerwacją a matchingiem, bo hold
  i match robią dwa różne procesy. Naprawialne, ale kosztem złożoności
  nieproporcjonalnej do efektu.
- Wariant 1: nie egzekwuje niczego (salda mogą zejść na minus) i awansuje
  `orders.trades` do źródła prawdy o pieniądzach, czego obecne gwarancje topicu nie
  udźwigną — crash po sync WAL-a a przed `producer.flush()` gubi trades bez
  republikacji, więc ledger rozjeżdża się z księgą trwale. Docelowo i tak do
  wyrzucenia po wejściu wariantu 3.

## Konsekwencje

- Risk check to lookup w mapie w wątku matchingu — koszt pomijalny, zero I/O w hot
  path; dochodzi za to kolejny punkt pomiarowy do badania latencji.
- Engine przestaje być czystym matchingiem — `OrderService` zyskuje stan sald,
  rezerwacje (hold przy place, zwolnienie przy cancel i przy fillu poniżej limitu)
  i odrzucanie zleceń bez pokrycia.
- WAL dostaje nowy kind rekordu (depozyt); format jest tagged union, więc stare
  journale replayują się bez zmian, ale downgrade wersji silnika wymaga wyczyszczenia
  journala.
- Znany gap utraty trades z `orders.trades` pozostaje kosmetyczny (dotyczy tylko
  market data) — salda odtwarzają się z WAL-a, nie z topicu.
- Trwały, audytowalny ledger w bazie (depozyty/wypłaty/historia) to osobna, przyszła
  warstwa downstream — nie zastępuje jej ten ADR.
