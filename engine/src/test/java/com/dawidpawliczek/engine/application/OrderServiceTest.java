package com.dawidpawliczek.engine.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dawidpawliczek.contracts.Asset;
import com.dawidpawliczek.contracts.CancelStatus;
import com.dawidpawliczek.contracts.Side;
import com.dawidpawliczek.contracts.Trade;
import com.dawidpawliczek.contracts.command.CancelOrderCommand;
import com.dawidpawliczek.contracts.command.DepositCommand;
import com.dawidpawliczek.contracts.command.OrderCommand;
import com.dawidpawliczek.contracts.command.PlaceOrderCommand;
import com.dawidpawliczek.contracts.event.AccountEvent;
import com.dawidpawliczek.contracts.event.CancelEvent;
import com.dawidpawliczek.contracts.event.DepositAccepted;
import com.dawidpawliczek.contracts.event.DepositRejected;
import com.dawidpawliczek.contracts.event.MarketEvent;
import com.dawidpawliczek.contracts.event.OrderRejected;
import com.dawidpawliczek.contracts.event.TradeEvent;
import com.dawidpawliczek.engine.ports.AccountFeedSink;
import com.dawidpawliczek.engine.ports.MarketFeedSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderServiceTest {

    private static final AccountFeedSink NO_OP_ACCOUNT_SINK = events -> {};
    private static final long SEED = 1_000_000;

    private static final class RecordingMarketFeedSink implements MarketFeedSink {
        private final List<MarketEvent> events = new ArrayList<>();

        @Override
        public void publish(List<MarketEvent> published) {
            events.addAll(published);
        }

        List<MarketEvent> drain() {
            var out = List.copyOf(events);
            events.clear();
            return out;
        }
    }

    private static final class RecordingAccountFeedSink implements AccountFeedSink {
        private final List<AccountEvent> events = new ArrayList<>();

        @Override
        public void publish(List<AccountEvent> published) {
            events.addAll(published);
        }

        List<AccountEvent> drain() {
            var out = List.copyOf(events);
            events.clear();
            return out;
        }
    }

    private static void submit(OrderService service, OrderCommand cmd, long offset) throws InterruptedException {
        service.submit(cmd, offset).join();
    }

    private static long seed(OrderService service, long offset, long... userIds) throws InterruptedException {
        for (long userId : userIds) {
            submit(service, new DepositCommand(userId, Asset.QUOTE, SEED), offset++);
            submit(service, new DepositCommand(userId, Asset.BASE, SEED), offset++);
        }
        return offset;
    }

    private static List<Trade> trades(List<MarketEvent> events) {
        return events.stream().map(e -> ((TradeEvent) e).trade()).toList();
    }

    private static PlaceOrderCommand sell(long userId, long price, long quantity) {
        return new PlaceOrderCommand(userId, Side.SELL, price, false, quantity);
    }

    private static PlaceOrderCommand buy(long userId, long price, long quantity) {
        return new PlaceOrderCommand(userId, Side.BUY, price, false, quantity);
    }

    private static List<List<Trade>> probeSequence(OrderService service, RecordingMarketFeedSink market)
            throws InterruptedException {
        var probes = new ArrayList<List<Trade>>();
        submit(service, buy(6, 99, 3), 21);
        probes.add(trades(market.drain()));
        submit(service, buy(7, 101, 4), 22);
        probes.add(trades(market.drain()));
        submit(service, sell(8, 100, 2), 23);
        probes.add(trades(market.drain()));
        return probes;
    }

    @Test
    void cancelRemovesRestingOrderSoLaterOrderDoesNotTrade() throws InterruptedException {
        var market = new RecordingMarketFeedSink();
        var service = new OrderService(new RecordingCommandLog(), market, NO_OP_ACCOUNT_SINK);
        seed(service, 0, 7, 9);

        submit(service, sell(7, 100, 5), 10);
        assertTrue(market.drain().isEmpty());

        submit(service, new CancelOrderCommand(7, 0), 11);
        assertEquals(CancelStatus.CANCELED, ((CancelEvent) market.drain().getFirst()).status());

        submit(service, buy(9, 100, 5), 12);
        assertTrue(market.drain().isEmpty());
        service.close();
    }

    @Test
    void tradeSettlesBothSides() throws InterruptedException {
        var market = new RecordingMarketFeedSink();
        var service = new OrderService(new RecordingCommandLog(), market, NO_OP_ACCOUNT_SINK);
        seed(service, 0, 7, 9);

        submit(service, sell(7, 100, 5), 10);
        submit(service, buy(9, 100, 5), 11);
        assertEquals(List.of(new Trade(0, 7, 1, 9, 100, 5)), trades(market.drain()));

        assertEquals(SEED + 500, service.ledger().cashOf(7));
        assertEquals(SEED - 5, service.ledger().assetOf(7));
        assertEquals(SEED - 500, service.ledger().cashOf(9));
        assertEquals(SEED + 5, service.ledger().assetOf(9));
        service.close();
    }

    @Test
    void priceImprovementRefundsTakerBuyer() throws InterruptedException {
        var market = new RecordingMarketFeedSink();
        var service = new OrderService(new RecordingCommandLog(), market, NO_OP_ACCOUNT_SINK);
        seed(service, 0, 7, 9);

        submit(service, sell(7, 100, 5), 10);
        submit(service, buy(9, 105, 5), 11);
        assertEquals(List.of(new Trade(0, 7, 1, 9, 100, 5)), trades(market.drain()));

        assertEquals(SEED - 500, service.ledger().cashOf(9));
        assertEquals(SEED + 5, service.ledger().assetOf(9));
        service.close();
    }

    @Test
    void nsfPlaceIsRejectedOnAccountFeedAndLedgerUntouched() throws InterruptedException {
        var market = new RecordingMarketFeedSink();
        var account = new RecordingAccountFeedSink();
        var service = new OrderService(new RecordingCommandLog(), market, account);

        submit(service, buy(5, 100, 5), 0);

        assertTrue(market.drain().isEmpty());
        var rejected = assertInstanceOf(OrderRejected.class, account.drain().getFirst());
        assertEquals(5, rejected.userId());
        assertEquals(0, service.ledger().cashOf(5));
        service.close();
    }

    @Test
    void cancelReleasesReservation() throws InterruptedException {
        var market = new RecordingMarketFeedSink();
        var service = new OrderService(new RecordingCommandLog(), market, NO_OP_ACCOUNT_SINK);
        seed(service, 0, 9);

        submit(service, buy(9, 100, 5), 10);
        assertEquals(SEED - 500, service.ledger().cashOf(9));

        submit(service, new CancelOrderCommand(9, 0), 11);
        assertEquals(CancelStatus.CANCELED, ((CancelEvent) market.drain().getFirst()).status());
        assertEquals(SEED, service.ledger().cashOf(9));
        service.close();
    }

    @Test
    void partialFillThenCancelReleasesExactlyTheRemainder() throws InterruptedException {
        var market = new RecordingMarketFeedSink();
        var service = new OrderService(new RecordingCommandLog(), market, NO_OP_ACCOUNT_SINK);
        seed(service, 0, 7, 9);

        submit(service, buy(9, 100, 10), 10);
        assertEquals(SEED - 1000, service.ledger().cashOf(9));

        submit(service, sell(7, 100, 6), 11);
        assertEquals(List.of(new Trade(0, 9, 1, 7, 100, 6)), trades(market.drain()));

        submit(service, new CancelOrderCommand(9, 0), 12);
        assertEquals(SEED - 600, service.ledger().cashOf(9));
        assertEquals(SEED + 6, service.ledger().assetOf(9));
        service.close();
    }

    @Test
    void marketSellReleasesUnfilledRemainder() throws InterruptedException {
        var market = new RecordingMarketFeedSink();
        var service = new OrderService(new RecordingCommandLog(), market, NO_OP_ACCOUNT_SINK);
        seed(service, 0, 7, 9);

        submit(service, buy(9, 100, 5), 10);
        submit(service, new PlaceOrderCommand(7, Side.SELL, 0, true, 8), 11);
        assertEquals(List.of(new Trade(0, 9, 1, 7, 100, 5)), trades(market.drain()));

        assertEquals(SEED - 5, service.ledger().assetOf(7));
        assertEquals(SEED + 500, service.ledger().cashOf(7));
        service.close();
    }

    @Test
    void marketBuyReservesExactBookCost() throws InterruptedException {
        var market = new RecordingMarketFeedSink();
        var service = new OrderService(new RecordingCommandLog(), market, NO_OP_ACCOUNT_SINK);
        seed(service, 0, 7, 8, 9);

        submit(service, sell(7, 100, 5), 10);
        submit(service, sell(8, 101, 5), 11);
        submit(service, new PlaceOrderCommand(9, Side.BUY, 0, true, 8), 12);
        assertEquals(List.of(new Trade(0, 7, 2, 9, 100, 5), new Trade(1, 8, 2, 9, 101, 3)), trades(market.drain()));

        assertEquals(SEED - 803, service.ledger().cashOf(9));
        assertEquals(SEED + 8, service.ledger().assetOf(9));
        service.close();
    }

    @Nested
    class Batched {

        private static Job job(OrderCommand cmd, long offset) {
            return new Job(cmd, offset, new CompletableFuture<>());
        }

        @Test
        void cancelBeforePlaceInOneBatchCompletesBothFutures() throws InterruptedException {
            var log = new RecordingCommandLog();
            var market = new RecordingMarketFeedSink();
            var service = new OrderService(log, market, NO_OP_ACCOUNT_SINK);
            seed(service, 0, 7);

            var cancelJob = job(new CancelOrderCommand(9, 999), 10);
            var placeJob = job(sell(7, 100, 5), 11);
            service.processBatch(List.of(cancelJob, placeJob));

            assertTrue(cancelJob.result().isDone());
            assertTrue(placeJob.result().isDone());
            var events = market.drain();
            assertEquals(1, events.size());
            assertEquals(CancelStatus.REJECTED, ((CancelEvent) events.getFirst()).status());
            assertEquals(4, log.size());
            assertEquals(11, service.lastSourceOffset());
            service.close();
        }

        @Test
        void placeCancelPlaceInOneBatchLeavesNothingToTrade() throws InterruptedException {
            var market = new RecordingMarketFeedSink();
            var service = new OrderService(new RecordingCommandLog(), market, NO_OP_ACCOUNT_SINK);
            seed(service, 0, 7, 9);

            var sellJob = job(sell(7, 100, 5), 10);
            var cancelJob = job(new CancelOrderCommand(7, 0), 11);
            var buyJob = job(buy(9, 100, 5), 12);
            service.processBatch(List.of(sellJob, cancelJob, buyJob));

            var events = market.drain();
            assertEquals(1, events.size());
            assertEquals(CancelStatus.CANCELED, ((CancelEvent) events.getFirst()).status());
            assertEquals(12, service.lastSourceOffset());
            service.close();
        }

        @Test
        void depositBeforeCrossingPlacesInOneBatchStillTrades() throws InterruptedException {
            var market = new RecordingMarketFeedSink();
            var account = new RecordingAccountFeedSink();
            var service = new OrderService(new RecordingCommandLog(), market, account);
            seed(service, 0, 7, 9);
            account.drain();

            var depositJob = job(new DepositCommand(42, Asset.QUOTE, 1000), 10);
            var sellJob = job(sell(7, 100, 5), 11);
            var buyJob = job(buy(9, 100, 5), 12);
            service.processBatch(List.of(depositJob, sellJob, buyJob));

            var accountEvents = account.drain();
            assertEquals(1, accountEvents.size());
            assertInstanceOf(DepositAccepted.class, accountEvents.getFirst());
            assertEquals(List.of(new Trade(0, 7, 1, 9, 100, 5)), trades(market.drain()));
            assertEquals(12, service.lastSourceOffset());
            service.close();
        }

        @Test
        void nsfInsideBatchDoesNotDisturbLaterCommands() throws InterruptedException {
            var market = new RecordingMarketFeedSink();
            var account = new RecordingAccountFeedSink();
            var service = new OrderService(new RecordingCommandLog(), market, account);
            seed(service, 0, 7, 9);
            account.drain();

            var brokeJob = job(buy(55, 100, 5), 10);
            var sellJob = job(sell(7, 100, 5), 11);
            var buyJob = job(buy(9, 100, 5), 12);
            service.processBatch(List.of(brokeJob, sellJob, buyJob));

            var rejected = assertInstanceOf(OrderRejected.class, account.drain().getFirst());
            assertEquals(55, rejected.userId());
            assertEquals(List.of(new Trade(1, 7, 2, 9, 100, 5)), trades(market.drain()));
            service.close();
        }

        @Test
        void placesBeforeCancelInOneBatchTradeAndReject() throws InterruptedException {
            var market = new RecordingMarketFeedSink();
            var service = new OrderService(new RecordingCommandLog(), market, NO_OP_ACCOUNT_SINK);
            seed(service, 0, 7, 9);

            var sellJob = job(sell(7, 100, 5), 10);
            var buyJob = job(buy(9, 100, 5), 11);
            var cancelJob = job(new CancelOrderCommand(9, 999), 12);
            service.processBatch(List.of(sellJob, buyJob, cancelJob));

            var events = market.drain();
            assertEquals(2, events.size());
            assertEquals(new Trade(0, 7, 1, 9, 100, 5), ((TradeEvent) events.getFirst()).trade());
            assertEquals(CancelStatus.REJECTED, ((CancelEvent) events.getLast()).status());
            assertEquals(12, service.lastSourceOffset());
            service.close();
        }

        @Test
        void duplicateOffsetInsideOneBatchIsDropped() throws InterruptedException {
            var log = new RecordingCommandLog();
            var market = new RecordingMarketFeedSink();
            var service = new OrderService(log, market, NO_OP_ACCOUNT_SINK);
            seed(service, 0, 7, 9);

            var sellJob = job(sell(7, 100, 5), 10);
            var duplicateJob = job(sell(7, 100, 5), 10);
            var buyJob = job(buy(9, 100, 5), 11);
            service.processBatch(List.of(sellJob, duplicateJob, buyJob));

            assertTrue(duplicateJob.result().isDone());
            assertEquals(List.of(new Trade(0, 7, 1, 9, 100, 5)), trades(market.drain()));
            assertEquals(6, log.size());
            assertEquals(11, service.lastSourceOffset());
            service.close();
        }
    }

    @Nested
    class Recovery {
        @Test
        void recoveryReappliesCancel() throws InterruptedException {
            var log = new RecordingCommandLog();
            var service1 = new OrderService(log, events -> {}, NO_OP_ACCOUNT_SINK);
            seed(service1, 0, 7, 9);
            submit(service1, sell(7, 100, 5), 10);
            submit(service1, new CancelOrderCommand(7, 0), 11);
            service1.close();

            var market = new RecordingMarketFeedSink();
            var service2 = new OrderService(log, market, NO_OP_ACCOUNT_SINK);
            assertEquals(11, service2.lastSourceOffset());

            submit(service2, buy(9, 100, 5), 12);
            assertTrue(market.drain().isEmpty());
            service2.close();
        }

        @Test
        void recoveryRebuildsBookIdCounterAndWatermark() throws InterruptedException {
            var log = new RecordingCommandLog();
            var service1 = new OrderService(log, events -> {}, NO_OP_ACCOUNT_SINK);
            seed(service1, 0, 7, 8, 9);
            submit(service1, sell(7, 100, 5), 10);
            submit(service1, sell(8, 101, 5), 11);
            service1.close();

            var market = new RecordingMarketFeedSink();
            var service2 = new OrderService(log, market, NO_OP_ACCOUNT_SINK);
            assertEquals(11, service2.lastSourceOffset());

            submit(service2, buy(9, 101, 10), 12);
            assertEquals(List.of(new Trade(0, 7, 2, 9, 100, 5), new Trade(1, 8, 2, 9, 101, 5)), trades(market.drain()));
            assertEquals(12, service2.lastSourceOffset());
            service2.close();
        }

        @Test
        void recoveryRebuildsBalancesAndReproducesRejections() throws InterruptedException {
            var log = new RecordingCommandLog();
            var service1 = new OrderService(log, events -> {}, NO_OP_ACCOUNT_SINK);
            seed(service1, 0, 7, 9);
            submit(service1, sell(7, 100, 5), 10);
            submit(service1, buy(9, 100, 5), 11);
            submit(service1, sell(55, 100, 5), 12);
            long cash7 = service1.ledger().cashOf(7);
            long asset7 = service1.ledger().assetOf(7);
            long cash9 = service1.ledger().cashOf(9);
            long asset9 = service1.ledger().assetOf(9);
            service1.close();

            var market = new RecordingMarketFeedSink();
            var service2 = new OrderService(log, market, NO_OP_ACCOUNT_SINK);
            assertEquals(12, service2.lastSourceOffset());
            assertEquals(cash7, service2.ledger().cashOf(7));
            assertEquals(asset7, service2.ledger().assetOf(7));
            assertEquals(cash9, service2.ledger().cashOf(9));
            assertEquals(asset9, service2.ledger().assetOf(9));
            assertEquals(0, service2.ledger().assetOf(55));

            submit(service2, buy(9, 100, 5), 13);
            assertTrue(market.drain().isEmpty());
            service2.close();
        }

        @Test
        void duplicateOffsetIsDropped() throws InterruptedException {
            var log = new RecordingCommandLog();
            var market = new RecordingMarketFeedSink();
            var service = new OrderService(log, market, NO_OP_ACCOUNT_SINK);
            seed(service, 0, 7, 9);

            submit(service, sell(7, 100, 5), 10);
            submit(service, sell(7, 100, 5), 10);
            assertTrue(market.drain().isEmpty());
            assertEquals(5, log.size());

            submit(service, buy(9, 100, 5), 11);
            assertEquals(List.of(new Trade(0, 7, 1, 9, 100, 5)), trades(market.drain()));

            submit(service, buy(9, 100, 1), 12);
            assertTrue(market.drain().isEmpty());
            service.close();

            var recoveredMarket = new RecordingMarketFeedSink();
            var recovered = new OrderService(log, recoveredMarket, NO_OP_ACCOUNT_SINK);
            submit(recovered, sell(7, 100, 5), 12);
            assertTrue(recoveredMarket.drain().isEmpty());
            assertEquals(7, log.size());
            recovered.close();
        }

        @Test
        void recoveryReappliesDeposit() throws InterruptedException {
            var log = new RecordingCommandLog();
            var service1 = new OrderService(log, events -> {}, NO_OP_ACCOUNT_SINK);
            submit(service1, new DepositCommand(42, Asset.QUOTE, Long.MAX_VALUE), 0);
            service1.close();

            var account = new RecordingAccountFeedSink();
            var service2 = new OrderService(log, events -> {}, account);
            assertEquals(0, service2.lastSourceOffset());
            assertTrue(account.drain().isEmpty());

            submit(service2, new DepositCommand(42, Asset.QUOTE, 1), 1);
            var rejected =
                    assertInstanceOf(DepositRejected.class, account.drain().getFirst());
            assertEquals(2, rejected.seq());
            assertEquals(42, rejected.userId());

            submit(service2, new DepositCommand(42, Asset.QUOTE, 1000), 0);
            assertTrue(account.drain().isEmpty());
            assertEquals(2, log.size());
            service2.close();
        }

        @Test
        void replayIsDeterministic() throws InterruptedException {
            var log = new RecordingCommandLog();
            var writer = new OrderService(log, events -> {}, NO_OP_ACCOUNT_SINK);
            seed(writer, 0, 1, 2, 3, 4, 5, 6, 7, 8);
            submit(writer, sell(1, 100, 5), 16);
            submit(writer, sell(2, 101, 7), 17);
            submit(writer, buy(3, 100, 4), 18);
            submit(writer, buy(4, 102, 6), 19);
            submit(writer, sell(5, 99, 3), 20);
            writer.close();

            var marketA = new RecordingMarketFeedSink();
            var marketB = new RecordingMarketFeedSink();
            var serviceA = new OrderService(log.copy(), marketA, NO_OP_ACCOUNT_SINK);
            var serviceB = new OrderService(log.copy(), marketB, NO_OP_ACCOUNT_SINK);
            assertEquals(serviceA.lastSourceOffset(), serviceB.lastSourceOffset());

            var probesA = probeSequence(serviceA, marketA);
            var probesB = probeSequence(serviceB, marketB);
            assertEquals(probesA, probesB);
            assertEquals(serviceA.lastSourceOffset(), serviceB.lastSourceOffset());
            for (long userId : new long[] {1, 2, 3, 4, 5, 6, 7, 8}) {
                assertEquals(serviceA.ledger().cashOf(userId), serviceB.ledger().cashOf(userId));
                assertEquals(
                        serviceA.ledger().assetOf(userId), serviceB.ledger().assetOf(userId));
            }
            serviceA.close();
            serviceB.close();
        }
    }
}
