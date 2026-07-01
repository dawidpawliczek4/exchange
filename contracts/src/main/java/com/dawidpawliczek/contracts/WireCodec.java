package com.dawidpawliczek.contracts;

import java.nio.ByteBuffer;

public final class WireCodec {

    private WireCodec() {}

    // userId(8) + side(1) + price(8) + market(1) + quantity(8)
    private static final int COMMAND_SIZE = 8 + 1 + 8 + 1 + 8;

    public static byte[] encode(PlaceOrderCommand cmd) {
        ByteBuffer b = ByteBuffer.allocate(COMMAND_SIZE);
        b.putLong(cmd.userId());
        b.put((byte) (cmd.side() == Side.SELL ? 0 : 1));
        b.putLong(cmd.price());
        b.put((byte) (cmd.market() ? 1 : 0));
        b.putLong(cmd.quantity());
        return b.array();
    }

    public static PlaceOrderCommand decodeCommand(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        long userId = b.getLong();
        Side side = b.get() == 0 ? Side.SELL : Side.BUY;
        long price = b.getLong();
        boolean market = b.get() == 1;
        long quantity = b.getLong();
        return new PlaceOrderCommand(userId, side, price, market, quantity);
    }

    // makerId(8) + makerUserId(8) + takerId(8) + takerUserId(8) + price(8) + quantity(8)
    private static final int TRADE_SIZE = 8 * 6;

    public static byte[] encode(Trade t) {
        ByteBuffer b = ByteBuffer.allocate(TRADE_SIZE);
        writeTradeBody(b, t);
        return b.array();
    }

    public static Trade decodeTrade(byte[] payload) {
        return readTradeBody(ByteBuffer.wrap(payload));
    }

    private static void writeTradeBody(ByteBuffer b, Trade t) {
        b.putLong(t.makerId());
        b.putLong(t.makerUserId());
        b.putLong(t.takerId());
        b.putLong(t.takerUserId());
        b.putLong(t.price());
        b.putLong(t.quantity());
    }

    private static Trade readTradeBody(ByteBuffer b) {
        return new Trade(b.getLong(), b.getLong(), b.getLong(), b.getLong(), b.getLong(), b.getLong());
    }

    // seq(8) + timestamp(8) + type(1), followed by the type-specific body
    private static final int EVENT_HEADER_SIZE = 8 + 8 + 1;
    private static final byte TYPE_TRADE = 0;

    public static byte[] encode(MarketEvent event) {
        return switch (event) {
            case TradeEvent te -> {
                ByteBuffer b = ByteBuffer.allocate(EVENT_HEADER_SIZE + TRADE_SIZE);
                b.putLong(te.seq());
                b.putLong(te.timestamp());
                b.put(TYPE_TRADE);
                writeTradeBody(b, te.trade());
                yield b.array();
            }
        };
    }

    public static MarketEvent decodeEvent(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        long seq = b.getLong();
        long timestamp = b.getLong();
        byte type = b.get();
        return switch (type) {
            case TYPE_TRADE -> new TradeEvent(seq, timestamp, readTradeBody(b));
            default -> throw new IllegalArgumentException("Unknown market event type: " + type);
        };
    }
}
