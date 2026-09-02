package com.dawidpawliczek.engine.wire;

import com.dawidpawliczek.contracts.Asset;

public record DepositRecord(long userId, Asset asset, long quantity, long sourceOffset, long timestamp)
        implements WalRecord {}
