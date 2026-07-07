package com.dawidpawliczek.engine.wire;

public record CancelRecord(long orderId, long userId, long sourceOffset) implements WalRecord {}
