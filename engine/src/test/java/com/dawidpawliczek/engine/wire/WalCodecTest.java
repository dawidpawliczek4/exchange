package com.dawidpawliczek.engine.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dawidpawliczek.contracts.Asset;
import com.dawidpawliczek.contracts.Side;
import com.dawidpawliczek.engine.domain.Order;
import org.junit.jupiter.api.Test;

class WalCodecTest {

    private static final long TS = 1_756_800_000_000L;

    @Test
    void orderRoundTrip() {
        var order = new Order(99, 42, Side.BUY, 100, false, 5);
        var encoded = WalCodec.encode(order, 7_000_000_123L, TS);
        assertEquals(51, encoded.length);

        var decoded = assertInstanceOf(PlaceRecord.class, WalCodec.decode(encoded));
        assertEquals(7_000_000_123L, decoded.sourceOffset());
        assertEquals(TS, decoded.timestamp());
        assertEquals(order.id(), decoded.order().id());
        assertEquals(order.userId(), decoded.order().userId());
        assertEquals(order.side(), decoded.order().side());
        assertEquals(order.price(), decoded.order().price());
        assertEquals(order.isMarket(), decoded.order().isMarket());
        assertEquals(order.quantity(), decoded.order().quantity());
    }

    @Test
    void cancelRoundTrip() {
        var encoded = WalCodec.encodeCancel(99, 1, 7_000_000_123L, TS);
        assertEquals(33, encoded.length);

        var decoded = assertInstanceOf(CancelRecord.class, WalCodec.decode(encoded));
        assertEquals(99, decoded.orderId());
        assertEquals(1, decoded.userId());
        assertEquals(7_000_000_123L, decoded.sourceOffset());
        assertEquals(TS, decoded.timestamp());
    }

    @Test
    void depositRoundTrip() {
        var encoded = WalCodec.encodeDeposit(1, Asset.BASE, 1500, 7_000_000_123L, TS);
        assertEquals(34, encoded.length);

        var decoded = assertInstanceOf(DepositRecord.class, WalCodec.decode(encoded));
        assertEquals(1500, decoded.quantity());
        assertEquals(1, decoded.userId());
        assertEquals(Asset.BASE, decoded.asset());
        assertEquals(7_000_000_123L, decoded.sourceOffset());
        assertEquals(TS, decoded.timestamp());
    }

    @Test
    void rejectsUnknownKind() {
        var stale = new byte[] {3};
        assertThrows(IllegalArgumentException.class, () -> WalCodec.decode(stale));
    }
}
