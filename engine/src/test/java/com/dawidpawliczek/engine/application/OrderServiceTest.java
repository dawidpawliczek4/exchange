package com.dawidpawliczek.engine.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dawidpawliczek.contracts.CancelStatus;
import com.dawidpawliczek.contracts.Side;
import com.dawidpawliczek.contracts.Trade;
import com.dawidpawliczek.contracts.command.CancelOrderCommand;
import com.dawidpawliczek.contracts.command.PlaceOrderCommand;
import com.dawidpawliczek.contracts.event.CancelEvent;
import com.dawidpawliczek.contracts.event.MarketEvent;
import com.dawidpawliczek.contracts.event.TradeEvent;
import com.dawidpawliczek.engine.ports.MarketFeedSink;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderServiceTest {

    private static final MarketFeedSink NO_OP_SINK = events -> {};

    private static List<MarketEvent> submit(OrderService service, PlaceOrderCommand cmd, long offset)
            throws InterruptedException {
        return service.submit(cmd, offset).join();
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

    private static List<MarketEvent> cancel(OrderService service, long userId, long orderId, long offset)
            throws InterruptedException {
        return service.submit(new CancelOrderCommand(userId, orderId), offset).join();
    }

    @Test
    void cancelRemovesRestingOrderSoLaterOrderDoesNotTrade() throws InterruptedException {
        var service = new OrderService(new RecordingCommandLog(), NO_OP_SINK);

        submit(service, sell(7, 100, 5), 0);
        var canceled = cancel(service, 7, 0, 1);
        assertEquals(CancelStatus.CANCELED, ((CancelEvent) canceled.getFirst()).status());

        assertTrue(submit(service, buy(9, 100, 5), 2).isEmpty());
        service.close();
    }

    @Test
    void recoveryReappliesCancel() throws InterruptedException {
        var log = new RecordingCommandLog();
        var service1 = new OrderService(log, NO_OP_SINK);
        submit(service1, sell(7, 100, 5), 0);
        cancel(service1, 7, 0, 1);
        service1.close();

        var service2 = new OrderService(log, NO_OP_SINK);
        assertEquals(1, service2.lastSourceOffset());

        assertTrue(submit(service2, buy(9, 100, 5), 2).isEmpty());
        service2.close();
    }

    @Test
    void recoveryRebuildsBookIdCounterAndWatermark() throws InterruptedException {
        var log = new RecordingCommandLog();
        var service1 = new OrderService(log, NO_OP_SINK);
        submit(service1, sell(7, 100, 5), 0);
        submit(service1, sell(8, 101, 5), 1);
        service1.close();

        var service2 = new OrderService(log, NO_OP_SINK);
        assertEquals(1, service2.lastSourceOffset());

        var probeTrades = trades(submit(service2, buy(9, 101, 10), 2));
        assertEquals(List.of(new Trade(0, 7, 2, 9, 100, 5), new Trade(1, 8, 2, 9, 101, 5)), probeTrades);
        assertEquals(2, service2.lastSourceOffset());
        service2.close();
    }

    @Test
    void duplicateOffsetIsDropped() throws InterruptedException {
        var log = new RecordingCommandLog();
        var service = new OrderService(log, NO_OP_SINK);

        submit(service, sell(7, 100, 5), 0);
        var duplicate = submit(service, sell(7, 100, 5), 0);
        assertTrue(duplicate.isEmpty());
        assertEquals(1, log.size());

        var probeTrades = trades(submit(service, buy(9, 100, 5), 1));
        assertEquals(List.of(new Trade(0, 7, 1, 9, 100, 5)), probeTrades);

        var leftoverProbe = submit(service, buy(9, 100, 1), 2);
        assertTrue(leftoverProbe.isEmpty());
        service.close();

        var recovered = new OrderService(log, NO_OP_SINK);
        var redelivered = submit(recovered, sell(7, 100, 5), 2);
        assertTrue(redelivered.isEmpty());
        assertEquals(3, log.size());
        recovered.close();
    }

    @Test
    void replayIsDeterministic() throws InterruptedException {
        var log = new RecordingCommandLog();
        var writer = new OrderService(log, NO_OP_SINK);
        submit(writer, sell(1, 100, 5), 0);
        submit(writer, sell(2, 101, 7), 1);
        submit(writer, buy(3, 100, 4), 2);
        submit(writer, buy(4, 102, 6), 3);
        submit(writer, sell(5, 99, 3), 4);
        writer.close();

        var serviceA = new OrderService(log.copy(), NO_OP_SINK);
        var serviceB = new OrderService(log.copy(), NO_OP_SINK);
        assertEquals(serviceA.lastSourceOffset(), serviceB.lastSourceOffset());

        var probesA = probeSequence(serviceA);
        var probesB = probeSequence(serviceB);
        assertEquals(probesA, probesB);
        assertEquals(serviceA.lastSourceOffset(), serviceB.lastSourceOffset());
        serviceA.close();
        serviceB.close();
    }

    private static List<List<Trade>> probeSequence(OrderService service) throws InterruptedException {
        return List.of(
                trades(submit(service, buy(6, 99, 3), 5)),
                trades(submit(service, buy(7, 101, 4), 6)),
                trades(submit(service, sell(8, 100, 2), 7)));
    }
}
