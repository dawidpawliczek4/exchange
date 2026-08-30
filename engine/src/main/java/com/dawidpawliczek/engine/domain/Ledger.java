package com.dawidpawliczek.engine.domain;

import com.dawidpawliczek.contracts.event.AccountEvent;
import com.dawidpawliczek.contracts.event.DepositAccepted;
import java.util.HashMap;
import java.util.function.LongSupplier;

public class Ledger {

    // TODO

    long seq = 0;
    LongSupplier clock;

    public Ledger() {
        this(System::currentTimeMillis);
    }

    public Ledger(LongSupplier clock) {
        this.clock = clock;
    }

    // userId -> Wallet
    HashMap<Long, Wallet> walletHashMap = new HashMap<>();

    public AccountEvent deposit(long userId, long quantity) {
        return new DepositAccepted(++seq, clock.getAsLong());
    }

    void reserve() {}

    void release() {}

    void settle() {}
}
