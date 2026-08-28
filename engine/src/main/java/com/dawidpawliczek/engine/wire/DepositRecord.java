package com.dawidpawliczek.engine.wire;

public record DepositRecord(long userId, long quantity, long sourceOffset) implements WalRecord {}
