package com.dawidpawliczek.contracts.event;

public sealed interface AccountEvent permits DepositAccepted, DepositRejected, OrderRejected {
    long userId();

    long seq();

    long timestamp();
}
