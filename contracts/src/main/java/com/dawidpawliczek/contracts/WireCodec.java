package com.dawidpawliczek.contracts;

import java.nio.ByteBuffer;

public final class WireCodec {

    private WireCodec() {}

    // place  (type 0, 27B): [1B type][8B userId][1B side][8B price][1B market][8B qty]
    // cancel (type 1, 17B): [1B type][8B userId][8B id]
    private static final int PLACE_COMMAND_SIZE = 1 + 8 + 1 + 8 + 1 + 8;
    private static final int CANCEL_COMMAND_SIZE = 1 + 8 + 8;
    private static final byte TYPE_ORDER_PLACE = 0;
    private static final byte TYPE_ORDER_CANCEL = 1;

    public static byte[] encode(OrderCommand orderCmd) {
        return switch (orderCmd) {
            case PlaceOrderCommand cmd -> {
                ByteBuffer b = ByteBuffer.allocate(PLACE_COMMAND_SIZE);
                b.put(TYPE_ORDER_PLACE);
                b.putLong(cmd.userId());
                b.put((byte) (cmd.side() == Side.SELL ? 0 : 1));
                b.putLong(cmd.price());
                b.put((byte) (cmd.market() ? 1 : 0));
                b.putLong(cmd.quantity());
                yield b.array();
            }
            case CancelOrderCommand cmd -> {
                ByteBuffer b = ByteBuffer.allocate(CANCEL_COMMAND_SIZE);
                b.put(TYPE_ORDER_CANCEL);
                b.putLong(cmd.userId());
                b.putLong(cmd.id());
                yield b.array();
            }
        };
    }

    public static OrderCommand decodeCommand(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        byte type = b.get();
        switch (type) {
            case TYPE_ORDER_PLACE -> {
                long userId = b.getLong();
                Side side = b.get() == 0 ? Side.SELL : Side.BUY;
                long price = b.getLong();
                boolean market = b.get() == 1;
                long quantity = b.getLong();
                return new PlaceOrderCommand(userId, side, price, market, quantity);
            }
            case TYPE_ORDER_CANCEL -> {
                long userId = b.getLong();
                long id = b.getLong();
                return new CancelOrderCommand(userId, id);
            }
            default -> throw new IllegalArgumentException("Unknown order command type: " + type);
        }
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
    // trade  (type 0): [header][48B trade]
    // cancel (type 1): [header][8B userId][8B orderId][1B status]
    private static final int EVENT_HEADER_SIZE = 8 + 8 + 1;
    private static final int CANCEL_EVENT_SIZE = 8 + 8 + 1;
    private static final byte TYPE_TRADE = 0;
    private static final byte TYPE_CANCEL = 1;
    private static final byte STATUS_CANCELED = 0;
    private static final byte STATUS_REJECTED = 1;

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
            case CancelEvent ce -> {
                ByteBuffer b = ByteBuffer.allocate(EVENT_HEADER_SIZE + CANCEL_EVENT_SIZE);
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

    public static MarketEvent decodeEvent(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        long seq = b.getLong();
        long timestamp = b.getLong();
        byte type = b.get();
        return switch (type) {
            case TYPE_TRADE -> new TradeEvent(seq, timestamp, readTradeBody(b));
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
