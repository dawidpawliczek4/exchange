package com.dawidpawliczek.engine.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.dawidpawliczek.contracts.event.MarketEvent;
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
        submit(service, buy(6, 99, 3), 5);
        probes.add(trades(market.drain()));
        submit(service, buy(7, 101, 4), 6);
        probes.add(trades(market.drain()));
        submit(service, sell(8, 100, 2), 7);
        probes.add(trades(market.drain()));
        return probes;
    }

    @Test
    void cancelRemovesRestingOrderSoLaterOrderDoesNotTrade() throws InterruptedException {
        var market = new RecordingMarketFeedSink();
        var service = new OrderService(new RecordingCommandLog(), market, NO_OP_ACCOUNT_SINK);

        submit(service, sell(7, 100, 5), 0);
        assertTrue(market.drain().isEmpty());

        submit(service, new CancelOrderCommand(7, 0), 1);
        assertEquals(CancelStatus.CANCELED, ((CancelEvent) market.drain().getFirst()).status());

        submit(service, buy(9, 100, 5), 2);
        assertTrue(market.drain().isEmpty());
        service.close();
    }

    @Nested
    class Batched {

        private static Job job(OrderCommand cmd, long offset) {
            return new Job(cmd, offset, new CompletableFuture<>());
        }

        @Test
        void cancelBeforePlaceInOneBatchCompletesBothFutures() {
            var log = new RecordingCommandLog();
            var market = new RecordingMarketFeedSink();
            var service = new OrderService(log, market, NO_OP_ACCOUNT_SINK);

            var cancelJob = job(new CancelOrderCommand(9, 999), 0);
            var placeJob = job(sell(7, 100, 5), 1);
            service.processBatch(List.of(cancelJob, placeJob));

            assertTrue(cancelJob.result().isDone());
            assertTrue(placeJob.result().isDone());
            var events = market.drain();
            assertEquals(1, events.size());
            assertEquals(CancelStatus.REJECTED, ((CancelEvent) events.getFirst()).status());
            assertEquals(2, log.size());
            assertEquals(1, service.lastSourceOffset());
            service.close();
        }

        @Test
        void placeCancelPlaceInOneBatchLeavesNothingToTrade() {
            var market = new RecordingMarketFeedSink();
            var service = new OrderService(new RecordingCommandLog(), market, NO_OP_ACCOUNT_SINK);

            var sellJob = job(sell(7, 100, 5), 0);
            var cancelJob = job(new CancelOrderCommand(7, 0), 1);
            var buyJob = job(buy(9, 100, 5), 2);
            service.processBatch(List.of(sellJob, cancelJob, buyJob));

            var events = market.drain();
            assertEquals(1, events.size());
            assertEquals(CancelStatus.CANCELED, ((CancelEvent) events.getFirst()).status());
            assertEquals(2, service.lastSourceOffset());
            service.close();
        }

        @Test
        void depositBeforeCrossingPlacesInOneBatchStillTrades() {
            var market = new RecordingMarketFeedSink();
            var account = new RecordingAccountFeedSink();
            var service = new OrderService(new RecordingCommandLog(), market, account);

            var depositJob = job(new DepositCommand(42, 1000), 0);
            var sellJob = job(sell(7, 100, 5), 1);
            var buyJob = job(buy(9, 100, 5), 2);
            service.processBatch(List.of(depositJob, sellJob, buyJob));

            var accountEvents = account.drain();
            assertEquals(1, accountEvents.size());
            assertInstanceOf(DepositAccepted.class, accountEvents.getFirst());
            assertEquals(List.of(new Trade(0, 7, 1, 9, 100, 5)), trades(market.drain()));
            assertEquals(2, service.lastSourceOffset());
            service.close();
        }

        @Test
        void placesBeforeCancelInOneBatchTradeAndReject() {
            var market = new RecordingMarketFeedSink();
            var service = new OrderService(new RecordingCommandLog(), market, NO_OP_ACCOUNT_SINK);

            var sellJob = job(sell(7, 100, 5), 0);
            var buyJob = job(buy(9, 100, 5), 1);
            var cancelJob = job(new CancelOrderCommand(9, 999), 2);
            service.processBatch(List.of(sellJob, buyJob, cancelJob));

            var events = market.drain();
            assertEquals(2, events.size());
            assertEquals(new Trade(0, 7, 1, 9, 100, 5), ((TradeEvent) events.getFirst()).trade());
            assertEquals(CancelStatus.REJECTED, ((CancelEvent) events.getLast()).status());
            assertEquals(2, service.lastSourceOffset());
            service.close();
        }

        @Test
        void duplicateOffsetInsideOneBatchIsDropped() {
            var log = new RecordingCommandLog();
            var market = new RecordingMarketFeedSink();
            var service = new OrderService(log, market, NO_OP_ACCOUNT_SINK);

            var sellJob = job(sell(7, 100, 5), 0);
            var duplicateJob = job(sell(7, 100, 5), 0);
            var buyJob = job(buy(9, 100, 5), 1);
            service.processBatch(List.of(sellJob, duplicateJob, buyJob));

            assertTrue(duplicateJob.result().isDone());
            assertEquals(List.of(new Trade(0, 7, 1, 9, 100, 5)), trades(market.drain()));
            assertEquals(2, log.size());
            assertEquals(1, service.lastSourceOffset());
            service.close();
        }
    }

    @Nested
    class Recovery {
        @Test
        void recoveryReappliesCancel() throws InterruptedException {
            var log = new RecordingCommandLog();
            var service1 = new OrderService(log, events -> {}, NO_OP_ACCOUNT_SINK);
            submit(service1, sell(7, 100, 5), 0);
            submit(service1, new CancelOrderCommand(7, 0), 1);
            service1.close();

            var market = new RecordingMarketFeedSink();
            var service2 = new OrderService(log, market, NO_OP_ACCOUNT_SINK);
            assertEquals(1, service2.lastSourceOffset());

            submit(service2, buy(9, 100, 5), 2);
            assertTrue(market.drain().isEmpty());
            service2.close();
        }

        @Test
        void recoveryRebuildsBookIdCounterAndWatermark() throws InterruptedException {
            var log = new RecordingCommandLog();
            var service1 = new OrderService(log, events -> {}, NO_OP_ACCOUNT_SINK);
            submit(service1, sell(7, 100, 5), 0);
            submit(service1, sell(8, 101, 5), 1);
            service1.close();

            var market = new RecordingMarketFeedSink();
            var service2 = new OrderService(log, market, NO_OP_ACCOUNT_SINK);
            assertEquals(1, service2.lastSourceOffset());

            submit(service2, buy(9, 101, 10), 2);
            assertEquals(List.of(new Trade(0, 7, 2, 9, 100, 5), new Trade(1, 8, 2, 9, 101, 5)), trades(market.drain()));
            assertEquals(2, service2.lastSourceOffset());
            service2.close();
        }

        @Test
        void duplicateOffsetIsDropped() throws InterruptedException {
            var log = new RecordingCommandLog();
            var market = new RecordingMarketFeedSink();
            var service = new OrderService(log, market, NO_OP_ACCOUNT_SINK);

            submit(service, sell(7, 100, 5), 0);
            submit(service, sell(7, 100, 5), 0);
            assertTrue(market.drain().isEmpty());
            assertEquals(1, log.size());

            submit(service, buy(9, 100, 5), 1);
            assertEquals(List.of(new Trade(0, 7, 1, 9, 100, 5)), trades(market.drain()));

            submit(service, buy(9, 100, 1), 2);
            assertTrue(market.drain().isEmpty());
            service.close();

            var recoveredMarket = new RecordingMarketFeedSink();
            var recovered = new OrderService(log, recoveredMarket, NO_OP_ACCOUNT_SINK);
            submit(recovered, sell(7, 100, 5), 2);
            assertTrue(recoveredMarket.drain().isEmpty());
            assertEquals(3, log.size());
            recovered.close();
        }

        @Test
        void replayIsDeterministic() throws InterruptedException {
            var log = new RecordingCommandLog();
            var writer = new OrderService(log, events -> {}, NO_OP_ACCOUNT_SINK);
            submit(writer, sell(1, 100, 5), 0);
            submit(writer, sell(2, 101, 7), 1);
            submit(writer, buy(3, 100, 4), 2);
            submit(writer, buy(4, 102, 6), 3);
            submit(writer, sell(5, 99, 3), 4);
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
            serviceA.close();
            serviceB.close();
        }
    }
}
