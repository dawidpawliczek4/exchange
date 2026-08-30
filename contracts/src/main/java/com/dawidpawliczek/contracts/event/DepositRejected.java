package com.dawidpawliczek.contracts.event;

public record DepositRejected(long seq, long timestamp) implements AccountEvent {}
