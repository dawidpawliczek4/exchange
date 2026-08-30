package com.dawidpawliczek.contracts.event;

import java.nio.ByteBuffer;

public final class AccountEventCodec {

    private AccountEventCodec() {}

    // seq(8) + timestamp(8) + type(1), followed by the type-specific body
    // deposit accepted (type 0): [header][8B userId]
    // deposit rejected (type 1): [header][8B userId]
    private static final int HEADER_SIZE = 8 + 8 + 1;
    private static final int DEPOSIT_BODY_SIZE = 8;
    private static final byte TYPE_DEPOSIT_ACCEPTED = 0;
    private static final byte TYPE_DEPOSIT_REJECTED = 1;

    public static byte[] encode(AccountEvent event) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_SIZE + DEPOSIT_BODY_SIZE);
        b.putLong(event.seq());
        b.putLong(event.timestamp());
        b.put(
                switch (event) {
                    case DepositAccepted a -> TYPE_DEPOSIT_ACCEPTED;
                    case DepositRejected r -> TYPE_DEPOSIT_REJECTED;
                });
        b.putLong(event.userId());
        return b.array();
    }

    public static AccountEvent decode(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        long seq = b.getLong();
        long timestamp = b.getLong();
        byte type = b.get();
        return switch (type) {
            case TYPE_DEPOSIT_ACCEPTED -> new DepositAccepted(seq, timestamp, b.getLong());
            case TYPE_DEPOSIT_REJECTED -> new DepositRejected(seq, timestamp, b.getLong());
            default -> throw new IllegalArgumentException("Unknown account event type: " + type);
        };
    }
}
