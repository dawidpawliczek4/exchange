package com.dawidpawliczek.engine.wire;

import com.dawidpawliczek.engine.domain.Order;

public record PlaceRecord(Order order, long sourceOffset) implements WalRecord {}
