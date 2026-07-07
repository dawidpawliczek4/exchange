package com.dawidpawliczek.contracts;

public record CancelEvent(long seq, long timestamp, long userId, long orderId, CancelStatus status)
        implements MarketEvent {}
