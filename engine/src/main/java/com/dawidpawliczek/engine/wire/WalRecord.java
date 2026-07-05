package com.dawidpawliczek.engine.wire;

import com.dawidpawliczek.engine.domain.Order;

public record WalRecord(Order order, long sourceOffset) {}
