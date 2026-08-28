package com.dawidpawliczek.engine.wire;

public sealed interface WalRecord permits CancelRecord, DepositRecord, PlaceRecord {
    long sourceOffset();
}
