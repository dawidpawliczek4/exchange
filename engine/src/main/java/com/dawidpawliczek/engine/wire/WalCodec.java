package com.dawidpawliczek.engine.wire;

import com.dawidpawliczek.contracts.Side;
import com.dawidpawliczek.engine.domain.Order;
import java.nio.ByteBuffer;

public final class WalCodec {

    private WalCodec() {}

    // id(8) + userId(8) + side(1) + price(8) + market(1) + quantity(8) — the WAL record (Order has an id)
    private static final int ORDER_SIZE = 8 + 8 + 1 + 8 + 1 + 8;

    public static byte[] encode(Order o) {
        ByteBuffer b = ByteBuffer.allocate(ORDER_SIZE);
        b.putLong(o.id());
        b.putLong(o.userId());
        b.put((byte) (o.side() == Side.SELL ? 0 : 1));
        b.putLong(o.price());
        b.put((byte) (o.isMarket() ? 1 : 0));
        b.putLong(o.quantity());
        return b.array();
    }

    public static Order decodeOrder(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        long id = b.getLong();
        long userId = b.getLong();
        Side side = b.get() == 0 ? Side.SELL : Side.BUY;
        long price = b.getLong();
        boolean market = b.get() == 1;
        long quantity = b.getLong();
        return new Order(id, userId, side, price, market, quantity);
    }
}
