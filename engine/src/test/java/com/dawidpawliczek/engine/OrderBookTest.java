package com.dawidpawliczek.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dawidpawliczek.engine.domain.Order;
import com.dawidpawliczek.engine.domain.OrderBook;
import com.dawidpawliczek.engine.domain.Side;
import com.dawidpawliczek.engine.domain.Trade;
import java.util.List;
import org.junit.jupiter.api.Test;

public class OrderBookTest {

    @Test
    void testLimitSellThenLimitBuy() {
        var ob = new OrderBook();

        var S1 = new Order(1, 10, Side.SELL, 100, false, 10);
        var S2 = new Order(2, 20, Side.SELL, 101, false, 8);

        var B1 = new Order(3, 30, Side.BUY, 100, false, 4);
        var B2 = new Order(4, 40, Side.BUY, 101, false, 9);

        var S1Trades = ob.submit(S1);
        assert (S1Trades.isEmpty());
        var S2Trades = ob.submit(S2);
        assert (S2Trades.isEmpty());

        var expectedB1Trades = List.of(new Trade(1, 10, 3, 30, 100, 4));
        var B1Trades = ob.submit(B1);
        assertEquals(expectedB1Trades, B1Trades);

        var expectedb2Trades = List.of(new Trade(1, 10, 4, 40, 100, 6), new Trade(2, 20, 4, 40, 101, 3));
        var b2Trades = ob.submit(B2);
        assertEquals(expectedb2Trades, b2Trades);
    }

    @Test
    void testCorrectQueueOrder() {
        var ob = new OrderBook();

        var S1 = new Order(1, 10, Side.SELL, 100, false, 10);
        var S2 = new Order(2, 20, Side.SELL, 100, false, 10);
        ob.submit(S1);
        ob.submit(S2);

        var b1 = new Order(3, 30, Side.BUY, 100, false, 12);
        var result = ob.submit(b1);

        var expectedb2 = List.of(new Trade(1, 10, 3, 30, 100, 10), new Trade(2, 20, 3, 30, 100, 2));
        assertEquals(expectedb2, result);
    }

    @Test
    void testMarketOrder() {
        var ob = new OrderBook();

        var S1 = new Order(1, 10, Side.SELL, 100, false, 10);
        var S2 = new Order(2, 20, Side.SELL, 100, false, 10);
        ob.submit(S1);
        ob.submit(S2);

        var b1 = new Order(3, 30, Side.BUY, 1, true, 12);
        var result = ob.submit(b1);
        var expectedb2 = List.of(new Trade(1, 10, 3, 30, 100, 10), new Trade(2, 20, 3, 30, 100, 2));

        assertEquals(expectedb2, result);
    }

    @Test
    void testTradeCarriesUserIds() {
        var ob = new OrderBook();

        // maker: user 10 sells, taker: user 30 buys
        var maker = new Order(1, 10, Side.SELL, 100, false, 5);
        var taker = new Order(2, 30, Side.BUY, 100, false, 5);

        ob.submit(maker);
        var trades = ob.submit(taker);

        assertEquals(1, trades.size());
        var t = trades.getFirst();

        assertEquals(1, t.makerId());
        assertEquals(10, t.makerUserId());
        assertEquals(2, t.takerId());
        assertEquals(30, t.takerUserId());
        assertEquals(100, t.price());
        assertEquals(5, t.quantity());
    }

    @Test
    void testSelfTradeKeepsBothUserIds() {
        var ob = new OrderBook();

        // same user (42) on both sides — engine doesn't block it, just records both
        var ask = new Order(1, 42, Side.SELL, 100, false, 3);
        var bid = new Order(2, 42, Side.BUY, 100, false, 3);

        ob.submit(ask);
        var trades = ob.submit(bid);

        assertEquals(List.of(new Trade(1, 42, 2, 42, 100, 3)), trades);
    }
}
