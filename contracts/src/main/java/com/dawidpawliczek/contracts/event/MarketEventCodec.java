package com.dawidpawliczek.contracts.event;

import com.dawidpawliczek.contracts.CancelStatus;
import com.dawidpawliczek.contracts.Trade;
import java.nio.ByteBuffer;

public final class MarketEventCodec {

    private MarketEventCodec() {}

    // seq(8) + timestamp(8) + type(1), followed by the type-specific body
    // trade  (type 0): [header][48B trade]
    // cancel (type 1): [header][8B userId][8B orderId][1B status]
    private static final int HEADER_SIZE = 8 + 8 + 1;
    private static final int TRADE_BODY_SIZE = 8 * 6;
    private static final int CANCEL_BODY_SIZE = 8 + 8 + 1;
    private static final byte TYPE_TRADE = 0;
    private static final byte TYPE_CANCEL = 1;
    private static final byte STATUS_CANCELED = 0;
    private static final byte STATUS_REJECTED = 1;

    public static byte[] encode(MarketEvent event) {
        return switch (event) {
            case TradeEvent te -> {
                ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + TRADE_BODY_SIZE);
                b.putLong(te.seq());
                b.putLong(te.timestamp());
                b.put(TYPE_TRADE);
                Trade t = te.trade();
                b.putLong(t.makerId());
                b.putLong(t.makerUserId());
                b.putLong(t.takerId());
                b.putLong(t.takerUserId());
                b.putLong(t.price());
                b.putLong(t.quantity());
                yield b.array();
            }
            case CancelEvent ce -> {
                ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + CANCEL_BODY_SIZE);
                b.putLong(ce.seq());
                b.putLong(ce.timestamp());
                b.put(TYPE_CANCEL);
                b.putLong(ce.userId());
                b.putLong(ce.orderId());
                b.put(statusToByte(ce.status()));
                yield b.array();
            }
        };
    }

    public static MarketEvent decode(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        long seq = b.getLong();
        long timestamp = b.getLong();
        byte type = b.get();
        return switch (type) {
            case TYPE_TRADE ->
                new TradeEvent(
                        seq,
                        timestamp,
                        new Trade(b.getLong(), b.getLong(), b.getLong(), b.getLong(), b.getLong(), b.getLong()));
            case TYPE_CANCEL -> new CancelEvent(seq, timestamp, b.getLong(), b.getLong(), statusFromByte(b.get()));
            default -> throw new IllegalArgumentException("Unknown market event type: " + type);
        };
    }

    private static byte statusToByte(CancelStatus status) {
        return switch (status) {
            case CANCELED -> STATUS_CANCELED;
            case REJECTED -> STATUS_REJECTED;
        };
    }

    private static CancelStatus statusFromByte(byte status) {
        return switch (status) {
            case STATUS_CANCELED -> CancelStatus.CANCELED;
            case STATUS_REJECTED -> CancelStatus.REJECTED;
            default -> throw new IllegalArgumentException("Unknown cancel status: " + status);
        };
    }
}
