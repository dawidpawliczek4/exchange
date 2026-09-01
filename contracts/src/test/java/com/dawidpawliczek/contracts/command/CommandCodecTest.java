package com.dawidpawliczek.contracts.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dawidpawliczek.contracts.Asset;
import com.dawidpawliczek.contracts.Side;
import org.junit.jupiter.api.Test;

class CommandCodecTest {

    @Test
    void commandRoundTrip() {
        var cmd = new PlaceOrderCommand(42, Side.BUY, 100, false, 5);
        assertEquals(cmd, CommandCodec.decode(CommandCodec.encode(cmd)));
    }

    @Test
    void commandRoundTripSellMarket() {
        var cmd = new PlaceOrderCommand(7, Side.SELL, 0, true, 99);
        assertEquals(cmd, CommandCodec.decode(CommandCodec.encode(cmd)));
    }

    @Test
    void cancelCommandRoundTrip() {
        var cmd = new CancelOrderCommand(42, 99);
        assertEquals(cmd, CommandCodec.decode(CommandCodec.encode(cmd)));
    }

    @Test
    void depositCommandRoundTrip() {
        var cmd = new DepositCommand(42, Asset.QUOTE, 99);
        assertEquals(cmd, CommandCodec.decode(CommandCodec.encode(cmd)));
    }

    @Test
    void depositCommandRoundTripBase() {
        var cmd = new DepositCommand(42, Asset.BASE, 99);
        assertEquals(cmd, CommandCodec.decode(CommandCodec.encode(cmd)));
    }

    @Test
    void rejectsUnknownCommandType() {
        var stale = new byte[17];
        stale[0] = 9;
        assertThrows(IllegalArgumentException.class, () -> CommandCodec.decode(stale));
    }
}
