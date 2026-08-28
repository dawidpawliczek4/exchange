package com.dawidpawliczek.contracts.command;

public sealed interface OrderCommand permits CancelOrderCommand, DepositCommand, PlaceOrderCommand {}
