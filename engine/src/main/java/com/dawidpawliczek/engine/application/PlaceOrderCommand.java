package com.dawidpawliczek.engine.application;

import com.dawidpawliczek.engine.domain.Side;

public record PlaceOrderCommand(
        long userId,
        Side side,
        long price,
        boolean market,
        long quantity
) { }
