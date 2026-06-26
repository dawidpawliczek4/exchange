package com.dawidpawliczek.engine.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dawidpawliczek.contracts.Side;
import com.dawidpawliczek.engine.domain.Order;
import org.junit.jupiter.api.Test;

class WalCodecTest {

    @Test
    void orderRoundTrip() {
        var order = new Order(99, 42, Side.BUY, 100, false, 5);
        var decoded = WalCodec.decodeOrder(WalCodec.encode(order));
        assertEquals(order.id(), decoded.id());
        assertEquals(order.userId(), decoded.userId());
        assertEquals(order.side(), decoded.side());
        assertEquals(order.price(), decoded.price());
        assertEquals(order.isMarket(), decoded.isMarket());
        assertEquals(order.quantity(), decoded.quantity());
    }
}
