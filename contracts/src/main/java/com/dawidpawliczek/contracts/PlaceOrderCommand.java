package com.dawidpawliczek.contracts;

public record PlaceOrderCommand(long userId, Side side, long price, boolean market, long quantity) {}
