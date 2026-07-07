package com.dawidpawliczek.contracts;

public record TradeEvent(long seq, long timestamp, Trade trade) implements MarketEvent {}
