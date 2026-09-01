package com.dawidpawliczek.contracts.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dawidpawliczek.contracts.Asset;
import com.dawidpawliczek.contracts.RejectReason;
import org.junit.jupiter.api.Test;

class AccountEventCodecTest {

    @Test
    void depositAcceptedRoundTrip() {
        var event = new DepositAccepted(7, 1_700_000_000_000L, 42, Asset.QUOTE);
        assertEquals(event, AccountEventCodec.decode(AccountEventCodec.encode(event)));
    }

    @Test
    void depositRejectedRoundTrip() {
        var event = new DepositRejected(8, 1_700_000_000_001L, 42, Asset.BASE);
        assertEquals(event, AccountEventCodec.decode(AccountEventCodec.encode(event)));
    }

    @Test
    void orderRejectedRoundTrip() {
        var event = new OrderRejected(9, 1_700_000_000_002L, 42, RejectReason.NSF);
        assertEquals(event, AccountEventCodec.decode(AccountEventCodec.encode(event)));
    }

    @Test
    void rejectsUnknownEventType() {
        var stale = new byte[26];
        stale[16] = 9;
        assertThrows(IllegalArgumentException.class, () -> AccountEventCodec.decode(stale));
    }
}
