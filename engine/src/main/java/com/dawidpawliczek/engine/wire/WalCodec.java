package com.dawidpawliczek.engine.wire;

import com.dawidpawliczek.contracts.Side;
import com.dawidpawliczek.engine.domain.Order;
import java.nio.ByteBuffer;

public final class WalCodec {

    private WalCodec() {}

    // version(1) + sourceOffset(8) + id(8) + userId(8) + side(1) + price(8) + market(1) + quantity(8)
    private static final byte VERSION = 1;
    private static final int RECORD_SIZE = 1 + 8 + 8 + 8 + 1 + 8 + 1 + 8;

    public static byte[] encode(Order o, long sourceOffset) {
        ByteBuffer b = ByteBuffer.allocate(RECORD_SIZE);
        b.put(VERSION);
        b.putLong(sourceOffset);
        b.putLong(o.id());
        b.putLong(o.userId());
        b.put((byte) (o.side() == Side.SELL ? 0 : 1));
        b.putLong(o.price());
        b.put((byte) (o.isMarket() ? 1 : 0));
        b.putLong(o.quantity());
        return b.array();
    }

    public static WalRecord decode(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        byte version = b.get();
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported WAL record version: " + version);
        }
        long sourceOffset = b.getLong();
        long id = b.getLong();
        long userId = b.getLong();
        Side side = b.get() == 0 ? Side.SELL : Side.BUY;
        long price = b.getLong();
        boolean market = b.get() == 1;
        long quantity = b.getLong();
        return new WalRecord(new Order(id, userId, side, price, market, quantity), sourceOffset);
    }
}
