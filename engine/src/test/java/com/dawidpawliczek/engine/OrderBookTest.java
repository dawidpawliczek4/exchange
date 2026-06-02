package com.dawidpawliczek.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class OrderBookTest {

    @Test
    void testLimitSellThenLimitBuy() {
        var ob = new OrderBook();

        var S1 = new Order(1, Side.SELL, 100, false, 10);
        var S2 = new Order(2, Side.SELL, 101, false, 8);

        var B1 = new Order(3, Side.BUY, 100, false, 4);
        var B2 = new Order(4, Side.BUY, 101, false, 9);

        var S1Trades = ob.submit(S1);
        assert(S1Trades.isEmpty());
        var S2Trades = ob.submit(S2);
        assert(S2Trades.isEmpty());


        var expectedB1Trades = List.of(
                new Trade(1, 3, 100, 4)
        );
        var B1Trades = ob.submit(B1);
        assertEquals(expectedB1Trades, B1Trades);

        var expectedb2Trades = List.of(
                new Trade(1, 4, 100, 6),
                new Trade(2, 4, 101, 3)
        );
        var b2Trades = ob.submit(B2);
        assertEquals(expectedb2Trades, b2Trades);
    }

    @Test
    void testCorrectQueueOrder() {
        var ob = new OrderBook();

        var S1 = new Order(1, Side.SELL, 100, false, 10);
        var S2 = new Order(2, Side.SELL, 100, false, 10);
        ob.submit(S1);
        ob.submit(S2);

        var b1 = new Order(3, Side.BUY, 100, false, 12);
        var result = ob.submit(b1);

        var expectedb2 = List.of(
                new Trade(1, 3, 100, 10),
                new Trade(2, 3, 100, 2)
        );
        assertEquals(expectedb2, result);
    }

    @Test
    void testMarketOrder() {
        var ob = new OrderBook();

        var S1 = new Order(1, Side.SELL, 100, false, 10);
        var S2 = new Order(2, Side.SELL, 100, false, 10);
        ob.submit(S1);
        ob.submit(S2);

        var b1 = new Order(3, Side.BUY, 1, true, 12);
        var result = ob.submit(b1);
        var expectedb2 = List.of(
                new Trade(1, 3, 100, 10),
                new Trade(2, 3, 100, 2)
        );

        assertEquals(expectedb2, result);
    }

}
