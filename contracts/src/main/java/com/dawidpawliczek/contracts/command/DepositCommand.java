package com.dawidpawliczek.contracts.command;

public record DepositCommand(long userId, long quantity) implements OrderCommand {}
