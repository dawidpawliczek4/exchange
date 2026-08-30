package com.dawidpawliczek.contracts.event;

public sealed interface AccountEvent permits DepositAccepted, DepositRejected {
    long userId();

    long seq();

    long timestamp();
}
