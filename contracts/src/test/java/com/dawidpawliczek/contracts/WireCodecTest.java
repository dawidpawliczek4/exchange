package com.dawidpawliczek.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void tradeRoundTrip() {
        var trade = new Trade(1, 10, 2, 20, 100, 5);
        assertEquals(trade, WireCodec.decodeTrade(WireCodec.encode(trade)));
    }

    @Test
    void tradeEventRoundTrip() {
        var event = new TradeEvent(7, 1_700_000_000_000L, new Trade(1, 10, 2, 20, 100, 5));
        assertEquals(event, WireCodec.decodeEvent(WireCodec.encode(event)));
    }
}
