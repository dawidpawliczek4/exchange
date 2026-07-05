package com.dawidpawliczek.engine.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dawidpawliczek.contracts.MarketEvent;
import com.dawidpawliczek.contracts.PlaceOrderCommand;
import com.dawidpawliczek.contracts.Side;
import com.dawidpawliczek.contracts.Trade;
import com.dawidpawliczek.contracts.TradeEvent;
import com.dawidpawliczek.engine.ports.MarketFeedSink;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderServiceTest {

    private static final MarketFeedSink NO_OP_SINK = events -> {};

    private static List<MarketEvent> place(OrderService service, PlaceOrderCommand cmd, long offset)
            throws InterruptedException {
        return service.place(cmd, offset).join();
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

    @Test
    void recoveryRebuildsBookIdCounterAndWatermark() throws InterruptedException {
        var log = new RecordingCommandLog();
        var service1 = new OrderService(log, NO_OP_SINK);
        place(service1, sell(7, 100, 5), 0);
        place(service1, sell(8, 101, 5), 1);
        service1.close();

        var service2 = new OrderService(log, NO_OP_SINK);
        assertEquals(1, service2.lastSourceOffset());

        var probeTrades = trades(place(service2, buy(9, 101, 10), 2));
        assertEquals(List.of(new Trade(0, 7, 2, 9, 100, 5), new Trade(1, 8, 2, 9, 101, 5)), probeTrades);
        assertEquals(2, service2.lastSourceOffset());
        service2.close();
    }

    @Test
    void duplicateOffsetIsDropped() throws InterruptedException {
        var log = new RecordingCommandLog();
        var service = new OrderService(log, NO_OP_SINK);

        place(service, sell(7, 100, 5), 0);
        var duplicate = place(service, sell(7, 100, 5), 0);
        assertTrue(duplicate.isEmpty());
        assertEquals(1, log.size());

        var probeTrades = trades(place(service, buy(9, 100, 5), 1));
        assertEquals(List.of(new Trade(0, 7, 1, 9, 100, 5)), probeTrades);

        var leftoverProbe = place(service, buy(9, 100, 1), 2);
        assertTrue(leftoverProbe.isEmpty());
        service.close();

        var recovered = new OrderService(log, NO_OP_SINK);
        var redelivered = place(recovered, sell(7, 100, 5), 2);
        assertTrue(redelivered.isEmpty());
        assertEquals(3, log.size());
        recovered.close();
    }

    @Test
    void replayIsDeterministic() throws InterruptedException {
        var log = new RecordingCommandLog();
        var writer = new OrderService(log, NO_OP_SINK);
        place(writer, sell(1, 100, 5), 0);
        place(writer, sell(2, 101, 7), 1);
        place(writer, buy(3, 100, 4), 2);
        place(writer, buy(4, 102, 6), 3);
        place(writer, sell(5, 99, 3), 4);
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
                trades(place(service, buy(6, 99, 3), 5)),
                trades(place(service, buy(7, 101, 4), 6)),
                trades(place(service, sell(8, 100, 2), 7)));
    }
}
