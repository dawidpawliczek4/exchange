package com.dawidpawliczek.contracts.event;

import com.dawidpawliczek.contracts.CancelStatus;

public record CancelEvent(long seq, long timestamp, long userId, long orderId, CancelStatus status)
        implements MarketEvent {}
