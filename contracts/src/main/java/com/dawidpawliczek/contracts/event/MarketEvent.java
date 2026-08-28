package com.dawidpawliczek.contracts.event;

public sealed interface MarketEvent permits CancelEvent, TradeEvent {
    long seq();

    long timestamp();
}
