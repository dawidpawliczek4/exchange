package com.dawidpawliczek.contracts.command;

public record CancelOrderCommand(long userId, long id) implements OrderCommand {}
