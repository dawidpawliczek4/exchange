package com.dawidpawliczek.contracts;

public record Trade(long makerId, long makerUserId, long takerId, long takerUserId, long price, long quantity) {}
