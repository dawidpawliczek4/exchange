package com.dawidpawliczek.contracts;

public sealed interface OrderCommand permits CancelOrderCommand, PlaceOrderCommand {}
