package com.dawidpawliczek.contracts.event;

import com.dawidpawliczek.contracts.Asset;

public record DepositRejected(long seq, long timestamp, long userId, Asset asset) implements AccountEvent {}
