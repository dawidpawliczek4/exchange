package com.dawidpawliczek.engine.wire;

public record CancelRecord(long orderId, long userId, long sourceOffset, long timestamp) implements WalRecord {}
