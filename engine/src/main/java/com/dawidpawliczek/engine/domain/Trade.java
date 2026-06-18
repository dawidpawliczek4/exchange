package com.dawidpawliczek.engine.domain;

public record Trade(long makerId, long makerUserId, long takerId, long takerUserId, long price, long quantity) {}
