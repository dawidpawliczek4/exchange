package com.dawidpawliczek.contracts.event;

import com.dawidpawliczek.contracts.Asset;
import com.dawidpawliczek.contracts.RejectReason;
import java.nio.ByteBuffer;

public final class AccountEventCodec {

    private AccountEventCodec() {}

    // seq(8) + timestamp(8) + type(1), followed by the type-specific body
    // deposit accepted (type 0): [header][8B userId][1B asset]
    // deposit rejected (type 1): [header][8B userId][1B asset]
    // order rejected   (type 2): [header][8B userId][1B reason]
    private static final int HEADER_SIZE = 8 + 8 + 1;
    private static final int BODY_SIZE = 8 + 1;
    private static final byte TYPE_DEPOSIT_ACCEPTED = 0;
    private static final byte TYPE_DEPOSIT_REJECTED = 1;
    private static final byte TYPE_ORDER_REJECTED = 2;

    public static byte[] encode(AccountEvent event) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + BODY_SIZE);
        b.putLong(event.seq());
        b.putLong(event.timestamp());
        switch (event) {
            case DepositAccepted a -> {
                b.put(TYPE_DEPOSIT_ACCEPTED);
                b.putLong(a.userId());
                b.put(assetByte(a.asset()));
            }
            case DepositRejected r -> {
                b.put(TYPE_DEPOSIT_REJECTED);
                b.putLong(r.userId());
                b.put(assetByte(r.asset()));
            }
            case OrderRejected r -> {
                b.put(TYPE_ORDER_REJECTED);
                b.putLong(r.userId());
                b.put((byte) r.reason().ordinal());
            }
        }
        return b.array();
    }

    public static AccountEvent decode(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        long seq = b.getLong();
        long timestamp = b.getLong();
        byte type = b.get();
        return switch (type) {
            case TYPE_DEPOSIT_ACCEPTED -> new DepositAccepted(seq, timestamp, b.getLong(), asset(b.get()));
            case TYPE_DEPOSIT_REJECTED -> new DepositRejected(seq, timestamp, b.getLong(), asset(b.get()));
            case TYPE_ORDER_REJECTED -> new OrderRejected(seq, timestamp, b.getLong(), RejectReason.values()[b.get()]);
            default -> throw new IllegalArgumentException("Unknown account event type: " + type);
        };
    }

    private static byte assetByte(Asset asset) {
        return (byte) (asset == Asset.QUOTE ? 0 : 1);
    }

    private static Asset asset(byte b) {
        return b == 0 ? Asset.QUOTE : Asset.BASE;
    }
}
