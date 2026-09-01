package com.dawidpawliczek.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dawidpawliczek.contracts.CancelStatus;
import com.dawidpawliczek.contracts.Side;
import com.dawidpawliczek.contracts.Trade;
import com.dawidpawliczek.contracts.event.CancelEvent;
import com.dawidpawliczek.contracts.event.TradeEvent;
import com.dawidpawliczek.engine.domain.Order;
import com.dawidpawliczek.engine.domain.OrderBook;
import java.util.List;
import org.junit.jupiter.api.Test;

public class OrderBookTest {

    @Test
    void testLimitSellThenLimitBuy() {
        var ob = new OrderBook(() -> 0L);

        var S1 = new Order(1, 10, Side.SELL, 100, false, 10);
        var S2 = new Order(2, 20, Side.SELL, 101, false, 8);

        var B1 = new Order(3, 30, Side.BUY, 100, false, 4);
        var B2 = new Order(4, 40, Side.BUY, 101, false, 9);

        var S1Trades = ob.submit(S1);
        assert (S1Trades.isEmpty());
        var S2Trades = ob.submit(S2);
        assert (S2Trades.isEmpty());

        var expectedB1Trades = List.of(new TradeEvent(1, 0, new Trade(1, 10, 3, 30, 100, 4)));
        var B1Trades = ob.submit(B1);
        assertEquals(expectedB1Trades, B1Trades);

        var expectedb2Trades = List.of(
                new TradeEvent(2, 0, new Trade(1, 10, 4, 40, 100, 6)),
                new TradeEvent(3, 0, new Trade(2, 20, 4, 40, 101, 3)));
        var b2Trades = ob.submit(B2);
        assertEquals(expectedb2Trades, b2Trades);
    }

    @Test
    void testCorrectQueueOrder() {
        var ob = new OrderBook(() -> 0L);

        var S1 = new Order(1, 10, Side.SELL, 100, false, 10);
        var S2 = new Order(2, 20, Side.SELL, 100, false, 10);
        ob.submit(S1);
        ob.submit(S2);

        var b1 = new Order(3, 30, Side.BUY, 100, false, 12);
        var result = ob.submit(b1);

        var expectedb2 = List.of(
                new TradeEvent(1, 0, new Trade(1, 10, 3, 30, 100, 10)),
                new TradeEvent(2, 0, new Trade(2, 20, 3, 30, 100, 2)));
        assertEquals(expectedb2, result);
    }

    @Test
    void testMarketOrder() {
        var ob = new OrderBook(() -> 0L);

        var S1 = new Order(1, 10, Side.SELL, 100, false, 10);
        var S2 = new Order(2, 20, Side.SELL, 100, false, 10);
        ob.submit(S1);
        ob.submit(S2);

        var b1 = new Order(3, 30, Side.BUY, 1, true, 12);
        var result = ob.submit(b1);
        var expectedb2 = List.of(
                new TradeEvent(1, 0, new Trade(1, 10, 3, 30, 100, 10)),
                new TradeEvent(2, 0, new Trade(2, 20, 3, 30, 100, 2)));

        assertEquals(expectedb2, result);
    }

    @Test
    void marketBuyRemainderDoesNotRest() {
        var ob = new OrderBook(() -> 0L);
        ob.submit(new Order(1, 10, Side.SELL, 100, false, 5));

        var trades = ob.submit(new Order(2, 30, Side.BUY, 0, true, 8));
        assertEquals(List.of(new TradeEvent(1, 0, new Trade(1, 10, 2, 30, 100, 5))), trades);

        assertTrue(ob.submit(new Order(3, 40, Side.SELL, 1, false, 1)).isEmpty());
    }

    @Test
    void marketSellRemainderDoesNotRest() {
        var ob = new OrderBook(() -> 0L);
        ob.submit(new Order(1, 10, Side.BUY, 100, false, 5));

        var trades = ob.submit(new Order(2, 30, Side.SELL, 0, true, 8));
        assertEquals(List.of(new TradeEvent(1, 0, new Trade(1, 10, 2, 30, 100, 5))), trades);

        assertTrue(ob.submit(new Order(3, 40, Side.BUY, Long.MAX_VALUE, false, 1)).isEmpty());
    }

    @Test
    void testTradeCarriesUserIdsAndSeq() {
        var ob = new OrderBook(() -> 0L);

        // maker: user 10 sells, taker: user 30 buys
        var maker = new Order(1, 10, Side.SELL, 100, false, 5);
        var taker = new Order(2, 30, Side.BUY, 100, false, 5);

        ob.submit(maker);
        var events = ob.submit(taker);

        assertEquals(1, events.size());
        var event = (TradeEvent) events.getFirst();

        assertEquals(1, event.seq());
        var t = event.trade();
        assertEquals(1, t.makerId());
        assertEquals(10, t.makerUserId());
        assertEquals(2, t.takerId());
        assertEquals(30, t.takerUserId());
        assertEquals(100, t.price());
        assertEquals(5, t.quantity());
    }

    @Test
    void testSelfTradeKeepsBothUserIds() {
        var ob = new OrderBook(() -> 0L);

        // same user (42) on both sides — engine doesn't block it, just records both
        var ask = new Order(1, 42, Side.SELL, 100, false, 3);
        var bid = new Order(2, 42, Side.BUY, 100, false, 3);

        ob.submit(ask);
        var trades = ob.submit(bid);

        assertEquals(List.of(new TradeEvent(1, 0, new Trade(1, 42, 2, 42, 100, 3))), trades);
    }

    @Test
    void cancelRemovesRestingSell() {
        var ob = new OrderBook(() -> 0L);
        ob.submit(new Order(1, 10, Side.SELL, 100, false, 10));

        assertEquals(
                new CancelEvent(1, 0, 10, 1, CancelStatus.CANCELED),
                ob.cancel(1, 10).event());

        assertTrue(ob.submit(new Order(2, 30, Side.BUY, 100, false, 10)).isEmpty());
    }

    @Test
    void cancelRemovesRestingBid() {
        var ob = new OrderBook(() -> 0L);
        ob.submit(new Order(1, 10, Side.BUY, 100, false, 10));

        assertEquals(
                new CancelEvent(1, 0, 10, 1, CancelStatus.CANCELED),
                ob.cancel(1, 10).event());

        assertTrue(ob.submit(new Order(2, 30, Side.SELL, 100, false, 10)).isEmpty());
    }

    @Test
    void cancelUnknownOrderIsRejected() {
        var ob = new OrderBook(() -> 0L);

        assertEquals(
                new CancelEvent(1, 0, 10, 999, CancelStatus.REJECTED),
                ob.cancel(999, 10).event());
    }

    @Test
    void cancelByNonOwnerIsRejectedAndOrderStays() {
        var ob = new OrderBook(() -> 0L);
        ob.submit(new Order(1, 10, Side.SELL, 100, false, 10));

        assertEquals(
                new CancelEvent(1, 0, 99, 1, CancelStatus.REJECTED),
                ob.cancel(1, 99).event());

        var trades = ob.submit(new Order(2, 30, Side.BUY, 100, false, 10));
        assertEquals(List.of(new TradeEvent(2, 0, new Trade(1, 10, 2, 30, 100, 10))), trades);
    }

    @Test
    void cancelRemovesRemainderAfterPartialFill() {
        var ob = new OrderBook(() -> 0L);
        ob.submit(new Order(1, 10, Side.SELL, 100, false, 10));
        ob.submit(new Order(2, 30, Side.BUY, 100, false, 4));

        assertEquals(
                new CancelEvent(2, 0, 10, 1, CancelStatus.CANCELED),
                ob.cancel(1, 10).event());

        assertTrue(ob.submit(new Order(3, 40, Side.BUY, 100, false, 6)).isEmpty());
    }
}
