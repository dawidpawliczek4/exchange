package com.dawidpawliczek.engine.wire;

import com.dawidpawliczek.engine.application.PlaceOrderCommand;
import com.dawidpawliczek.engine.domain.Order;
import com.dawidpawliczek.engine.domain.Side;
import com.dawidpawliczek.engine.domain.Trade;
import java.nio.ByteBuffer;

public final class MessageCodec {

    private MessageCodec() {}

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
        b.putLong(t.makerId());
        b.putLong(t.makerUserId());
        b.putLong(t.takerId());
        b.putLong(t.takerUserId());
        b.putLong(t.price());
        b.putLong(t.quantity());
        return b.array();
    }

    public static Trade decodeTrade(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        return new Trade(b.getLong(), b.getLong(), b.getLong(), b.getLong(), b.getLong(), b.getLong());
    }

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
