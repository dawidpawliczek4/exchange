package com.dawidpawliczek.contracts.command;

import com.dawidpawliczek.contracts.Asset;

public record DepositCommand(long userId, Asset asset, long quantity) implements OrderCommand {}
