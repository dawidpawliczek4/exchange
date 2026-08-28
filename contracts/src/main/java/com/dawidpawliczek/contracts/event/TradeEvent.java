package com.dawidpawliczek.contracts.event;

import com.dawidpawliczek.contracts.Trade;

public record TradeEvent(long seq, long timestamp, Trade trade) implements MarketEvent {}
