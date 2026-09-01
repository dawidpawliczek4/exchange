package com.dawidpawliczek.contracts.event;

import com.dawidpawliczek.contracts.RejectReason;

public record OrderRejected(long seq, long timestamp, long userId, RejectReason reason) implements AccountEvent {}
