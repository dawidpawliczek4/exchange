package com.dawidpawliczek.contracts.event;

import com.dawidpawliczek.contracts.Asset;

public record DepositAccepted(long seq, long timestamp, long userId, Asset asset) implements AccountEvent {}
