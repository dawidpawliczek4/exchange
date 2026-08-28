package com.dawidpawliczek.contracts.command;

import com.dawidpawliczek.contracts.Side;

public record PlaceOrderCommand(long userId, Side side, long price, boolean market, long quantity)
        implements OrderCommand {}
