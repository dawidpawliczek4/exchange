package com.dawidpawliczek.engine.wire;

import com.dawidpawliczek.contracts.Side;
import com.dawidpawliczek.engine.domain.Order;
import java.nio.ByteBuffer;

public final class WalCodec {

    private WalCodec() {}

    // cancel (tag 0, 25B): [1B tag][8B sourceOffset][8B orderId][8B userId]
    // place  (tag 1, 43B): [1B tag][8B sourceOffset][8B id][8B userId][1B side][8B price][1B market][8B qty]
    private static final byte TAG_CANCEL = 0;
    private static final byte TAG_PLACE = 1;
    private static final int CANCEL_SIZE = 1 + 8 + 8 + 8;
    private static final int PLACE_SIZE = 1 + 8 + 8 + 8 + 1 + 8 + 1 + 8;

    public static byte[] encode(Order o, long sourceOffset) {
        ByteBuffer b = ByteBuffer.allocate(PLACE_SIZE);
        b.put(TAG_PLACE);
        b.putLong(sourceOffset);
        b.putLong(o.id());
        b.putLong(o.userId());
        b.put((byte) (o.side() == Side.SELL ? 0 : 1));
        b.putLong(o.price());
        b.put((byte) (o.isMarket() ? 1 : 0));
        b.putLong(o.quantity());
        return b.array();
    }

    public static byte[] encodeDeposit(long userId, long quantity, long sourceOffset) {
        // TODO
        return new byte[] {};
    }

    public static byte[] encodeCancel(long orderId, long userId, long sourceOffset) {
        ByteBuffer b = ByteBuffer.allocate(CANCEL_SIZE);
        b.put(TAG_CANCEL);
        b.putLong(sourceOffset);
        b.putLong(orderId);
        b.putLong(userId);
        return b.array();
    }

    public static WalRecord decode(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        byte tag = b.get();
        return switch (tag) {
            case TAG_PLACE -> {
                long sourceOffset = b.getLong();
                long id = b.getLong();
                long userId = b.getLong();
                Side side = b.get() == 0 ? Side.SELL : Side.BUY;
                long price = b.getLong();
                boolean market = b.get() == 1;
                long quantity = b.getLong();
                yield new PlaceRecord(new Order(id, userId, side, price, market, quantity), sourceOffset);
            }
            case TAG_CANCEL -> {
                long sourceOffset = b.getLong();
                long orderId = b.getLong();
                long userId = b.getLong();
                yield new CancelRecord(orderId, userId, sourceOffset);
            }
            default -> throw new IllegalArgumentException("unsupported WAL record kind: " + tag);
        };
    }
}
