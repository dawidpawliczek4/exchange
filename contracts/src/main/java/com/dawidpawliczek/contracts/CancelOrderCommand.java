package com.dawidpawliczek.contracts;

public record CancelOrderCommand(long userId, long id) implements OrderCommand {}
