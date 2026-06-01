package com.dawidpawliczek.engine;

public record Trade(long makerId, long takerId, long price, long quantity) { }