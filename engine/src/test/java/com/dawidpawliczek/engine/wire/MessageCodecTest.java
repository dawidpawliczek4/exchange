package com.dawidpawliczek.engine.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dawidpawliczek.engine.application.PlaceOrderCommand;
import com.dawidpawliczek.engine.domain.Order;
import com.dawidpawliczek.engine.domain.Side;
import com.dawidpawliczek.engine.domain.Trade;
import org.junit.jupiter.api.Test;

class MessageCodecTest {

    @Test
    void commandRoundTrip() {
        var cmd = new PlaceOrderCommand(42, Side.BUY, 100, false, 5);
        assertEquals(cmd, MessageCodec.decodeCommand(MessageCodec.encode(cmd)));
    }

    @Test
    void commandRoundTripSellMarket() {
        var cmd = new PlaceOrderCommand(7, Side.SELL, 0, true, 99);
        assertEquals(cmd, MessageCodec.decodeCommand(MessageCodec.encode(cmd)));
    }

    @Test
    void tradeRoundTrip() {
        var trade = new Trade(1, 10, 2, 20, 100, 5);
        assertEquals(trade, MessageCodec.decodeTrade(MessageCodec.encode(trade)));
    }

    @Test
    void orderRoundTrip() {
        var order = new Order(99, 42, Side.BUY, 100, false, 5);
        var decoded = MessageCodec.decodeOrder(MessageCodec.encode(order));
        assertEquals(order.id(), decoded.id());
        assertEquals(order.userId(), decoded.userId());
        assertEquals(order.side(), decoded.side());
        assertEquals(order.price(), decoded.price());
        assertEquals(order.isMarket(), decoded.isMarket());
        assertEquals(order.quantity(), decoded.quantity());
    }
}
