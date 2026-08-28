package com.dawidpawliczek.contracts.command;

import com.dawidpawliczek.contracts.Side;
import java.nio.ByteBuffer;

public final class CommandCodec {

    private CommandCodec() {}

    // place  (type 0, 27B): [1B type][8B userId][1B side][8B price][1B market][8B qty]
    // cancel (type 1, 17B): [1B type][8B userId][8B id]
    // deposit (type 2, 17B) : [1B type][8B userId][8B quantity]
    private static final int PLACE_SIZE = 1 + 8 + 1 + 8 + 1 + 8;
    private static final int CANCEL_SIZE = 1 + 8 + 8;
    private static final int DEPOSIT_SIZE = 1 + 8 + 8;
    private static final byte TYPE_PLACE = 0;
    private static final byte TYPE_CANCEL = 1;
    private static final byte TYPE_DEPOSIT = 2;

    public static byte[] encode(OrderCommand orderCmd) {
        return switch (orderCmd) {
            case PlaceOrderCommand cmd -> {
                ByteBuffer b = ByteBuffer.allocate(PLACE_SIZE);
                b.put(TYPE_PLACE);
                b.putLong(cmd.userId());
                b.put((byte) (cmd.side() == Side.SELL ? 0 : 1));
                b.putLong(cmd.price());
                b.put((byte) (cmd.market() ? 1 : 0));
                b.putLong(cmd.quantity());
                yield b.array();
            }
            case CancelOrderCommand cmd -> {
                ByteBuffer b = ByteBuffer.allocate(CANCEL_SIZE);
                b.put(TYPE_CANCEL);
                b.putLong(cmd.userId());
                b.putLong(cmd.id());
                yield b.array();
            }
            case DepositCommand cmd -> {
                ByteBuffer b = ByteBuffer.allocate(DEPOSIT_SIZE);
                b.put(TYPE_DEPOSIT);
                b.putLong(cmd.userId());
                b.putLong(cmd.quantity());
                yield b.array();
            }
        };
    }

    public static OrderCommand decode(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        byte type = b.get();
        switch (type) {
            case TYPE_PLACE -> {
                long userId = b.getLong();
                Side side = b.get() == 0 ? Side.SELL : Side.BUY;
                long price = b.getLong();
                boolean market = b.get() == 1;
                long quantity = b.getLong();
                return new PlaceOrderCommand(userId, side, price, market, quantity);
            }
            case TYPE_CANCEL -> {
                long userId = b.getLong();
                long id = b.getLong();
                return new CancelOrderCommand(userId, id);
            }
            case TYPE_DEPOSIT -> {
                long userId = b.getLong();
                long quantity = b.getLong();
                return new DepositCommand(userId, quantity);
            }
            default -> throw new IllegalArgumentException("Unknown order command type: " + type);
        }
    }
}
