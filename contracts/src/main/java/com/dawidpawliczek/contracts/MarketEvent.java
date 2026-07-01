package com.dawidpawliczek.contracts;

public sealed interface MarketEvent permits TradeEvent {
    long seq();

    long timestamp();

    MarketEventType type();
}
