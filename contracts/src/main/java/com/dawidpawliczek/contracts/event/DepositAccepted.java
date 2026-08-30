package com.dawidpawliczek.contracts.event;

public record DepositAccepted(long seq, long timestamp) implements AccountEvent {}
