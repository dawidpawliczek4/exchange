package com.dawidpawliczek.contracts;

public sealed interface MarketEvent permits CancelEvent, TradeEvent {
    long seq();

    long timestamp();
}
