package com.dawidpawliczek.engine.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dawidpawliczek.contracts.Side;
import com.dawidpawliczek.engine.domain.Order;
import org.junit.jupiter.api.Test;

class WalCodecTest {

    @Test
    void orderRoundTrip() {
        var order = new Order(99, 42, Side.BUY, 100, false, 5);
        var encoded = WalCodec.encode(order, 7_000_000_123L);
        assertEquals(43, encoded.length);

        var decoded = WalCodec.decode(encoded);
        assertEquals(7_000_000_123L, decoded.sourceOffset());
        assertEquals(order.id(), decoded.order().id());
        assertEquals(order.userId(), decoded.order().userId());
        assertEquals(order.side(), decoded.order().side());
        assertEquals(order.price(), decoded.order().price());
        assertEquals(order.isMarket(), decoded.order().isMarket());
        assertEquals(order.quantity(), decoded.order().quantity());
    }

    @Test
    void rejectsUnknownVersion() {
        var stale = new byte[43];
        assertThrows(IllegalArgumentException.class, () -> WalCodec.decode(stale));
    }
}
