package com.dawidpawliczek.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WireCodecTest {

    @Test
    void commandRoundTrip() {
        var cmd = new PlaceOrderCommand(42, Side.BUY, 100, false, 5);
        assertEquals(cmd, WireCodec.decodeCommand(WireCodec.encode(cmd)));
    }

    @Test
    void commandRoundTripSellMarket() {
        var cmd = new PlaceOrderCommand(7, Side.SELL, 0, true, 99);
        assertEquals(cmd, WireCodec.decodeCommand(WireCodec.encode(cmd)));
    }

    @Test
    void cancelCommandRoundTrip() {
        var cmd = new CancelOrderCommand(42, 99);
        assertEquals(cmd, WireCodec.decodeCommand(WireCodec.encode(cmd)));
    }

    @Test
    void tradeRoundTrip() {
        var trade = new Trade(1, 10, 2, 20, 100, 5);
        assertEquals(trade, WireCodec.decodeTrade(WireCodec.encode(trade)));
    }

    @Test
    void tradeEventRoundTrip() {
        var event = new TradeEvent(7, 1_700_000_000_000L, new Trade(1, 10, 2, 20, 100, 5));
        assertEquals(event, WireCodec.decodeEvent(WireCodec.encode(event)));
    }

    @Test
    void cancelEventRoundTrip() {
        var rejected = new CancelEvent(7, 1_700_000_000_000L, 42, 99, CancelStatus.REJECTED);
        assertEquals(rejected, WireCodec.decodeEvent(WireCodec.encode(rejected)));

        var canceled = new CancelEvent(8, 1_700_000_000_001L, 42, 99, CancelStatus.CANCELED);
        assertEquals(canceled, WireCodec.decodeEvent(WireCodec.encode(canceled)));
    }

    @Test
    void rejectsUnknownEventType() {
        var stale = new byte[17];
        stale[16] = 9;
        assertThrows(IllegalArgumentException.class, () -> WireCodec.decodeEvent(stale));
    }
}
