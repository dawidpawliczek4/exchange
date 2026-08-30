package com.dawidpawliczek.engine.domain;

import com.dawidpawliczek.contracts.event.AccountEvent;
import com.dawidpawliczek.contracts.event.DepositAccepted;
import com.dawidpawliczek.contracts.event.DepositRejected;
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
        if (quantity > 0 && canAdd(quantity, cashOf(userId))) {
            walletHashMap.computeIfAbsent(userId, _ -> new Wallet()).cash += quantity;
            return new DepositAccepted(++seq, clock.getAsLong(), userId);
        } else {
            return new DepositRejected(++seq, clock.getAsLong(), userId);
        }
    }

    static boolean canAdd(long a, long b) {
        return b <= 0 ? Long.MIN_VALUE - b <= a : Long.MAX_VALUE - b >= a;
    }

    long cashOf(long userId) {
        Wallet wallet = walletHashMap.get(userId);
        return wallet == null ? 0 : wallet.cash;
    }

    void reserve() {}

    void release() {}

    void settle() {}
}
