package com.dawidpawliczek.engine.wire;

public sealed interface WalRecord permits PlaceRecord, CancelRecord {
    long sourceOffset();
}
