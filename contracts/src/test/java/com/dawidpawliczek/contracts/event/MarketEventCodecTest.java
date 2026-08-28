package com.dawidpawliczek.contracts.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dawidpawliczek.contracts.CancelStatus;
import com.dawidpawliczek.contracts.Trade;
import org.junit.jupiter.api.Test;

class MarketEventCodecTest {

    @Test
    void tradeEventRoundTrip() {
        var event = new TradeEvent(7, 1_700_000_000_000L, new Trade(1, 10, 2, 20, 100, 5));
        assertEquals(event, MarketEventCodec.decode(MarketEventCodec.encode(event)));
    }

    @Test
    void cancelEventRoundTrip() {
        var rejected = new CancelEvent(7, 1_700_000_000_000L, 42, 99, CancelStatus.REJECTED);
        assertEquals(rejected, MarketEventCodec.decode(MarketEventCodec.encode(rejected)));

        var canceled = new CancelEvent(8, 1_700_000_000_001L, 42, 99, CancelStatus.CANCELED);
        assertEquals(canceled, MarketEventCodec.decode(MarketEventCodec.encode(canceled)));
    }

    @Test
    void rejectsUnknownEventType() {
        var stale = new byte[17];
        stale[16] = 9;
        assertThrows(IllegalArgumentException.class, () -> MarketEventCodec.decode(stale));
    }
}
