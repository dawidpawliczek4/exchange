package com.dawidpawliczek.engine.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dawidpawliczek.contracts.event.DepositAccepted;
import com.dawidpawliczek.contracts.event.DepositRejected;
import org.junit.jupiter.api.Test;

class LedgerTest {

    private final Ledger ledger = new Ledger(() -> 0L);

    @Test
    void depositCreditsCashAndEmitsAccepted() {
        assertEquals(new DepositAccepted(1, 0, 42), ledger.deposit(42, 1000));
        assertEquals(1000, ledger.cashOf(42));
    }

    @Test
    void depositsAccumulatePerUser() {
        ledger.deposit(42, 1000);
        assertEquals(new DepositAccepted(2, 0, 42), ledger.deposit(42, 500));
        ledger.deposit(7, 300);
        assertEquals(1500, ledger.cashOf(42));
        assertEquals(300, ledger.cashOf(7));
    }

    @Test
    void zeroQuantityIsRejected() {
        assertEquals(new DepositRejected(1, 0, 42), ledger.deposit(42, 0));
        assertEquals(0, ledger.cashOf(42));
    }

    @Test
    void negativeQuantityIsRejected() {
        assertEquals(new DepositRejected(1, 0, 42), ledger.deposit(42, -5));
        assertEquals(0, ledger.cashOf(42));
    }

    @Test
    void overflowIsRejectedAndBalanceUntouched() {
        ledger.deposit(42, Long.MAX_VALUE);
        assertEquals(new DepositRejected(2, 0, 42), ledger.deposit(42, 1));
        assertEquals(Long.MAX_VALUE, ledger.cashOf(42));
    }
}
