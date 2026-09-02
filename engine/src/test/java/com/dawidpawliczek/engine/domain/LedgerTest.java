package com.dawidpawliczek.engine.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dawidpawliczek.contracts.Asset;
import com.dawidpawliczek.contracts.RejectReason;
import com.dawidpawliczek.contracts.event.DepositAccepted;
import com.dawidpawliczek.contracts.event.DepositRejected;
import com.dawidpawliczek.contracts.event.OrderRejected;
import org.junit.jupiter.api.Test;

class LedgerTest {

    private final Ledger ledger = new Ledger();

    @Test
    void depositCreditsCashAndEmitsAccepted() {
        assertEquals(new DepositAccepted(1, 0, 42, Asset.QUOTE), ledger.deposit(42, Asset.QUOTE, 1000, 0));
        assertEquals(1000, ledger.cashOf(42));
        assertEquals(0, ledger.assetOf(42));
    }

    @Test
    void depositCreditsAssetIndependentlyOfCash() {
        ledger.deposit(42, Asset.QUOTE, 1000, 0);
        assertEquals(new DepositAccepted(2, 0, 42, Asset.BASE), ledger.deposit(42, Asset.BASE, 7, 0));
        assertEquals(1000, ledger.cashOf(42));
        assertEquals(7, ledger.assetOf(42));
    }

    @Test
    void depositsAccumulatePerUser() {
        ledger.deposit(42, Asset.QUOTE, 1000, 0);
        assertEquals(new DepositAccepted(2, 0, 42, Asset.QUOTE), ledger.deposit(42, Asset.QUOTE, 500, 0));
        ledger.deposit(7, Asset.QUOTE, 300, 0);
        assertEquals(1500, ledger.cashOf(42));
        assertEquals(300, ledger.cashOf(7));
    }

    @Test
    void zeroQuantityIsRejected() {
        assertEquals(new DepositRejected(1, 0, 42, Asset.QUOTE), ledger.deposit(42, Asset.QUOTE, 0, 0));
        assertEquals(0, ledger.cashOf(42));
    }

    @Test
    void negativeQuantityIsRejected() {
        assertEquals(new DepositRejected(1, 0, 42, Asset.QUOTE), ledger.deposit(42, Asset.QUOTE, -5, 0));
        assertEquals(0, ledger.cashOf(42));
    }

    @Test
    void overflowIsRejectedAndBalanceUntouched() {
        ledger.deposit(42, Asset.QUOTE, Long.MAX_VALUE, 0);
        assertEquals(new DepositRejected(2, 0, 42, Asset.QUOTE), ledger.deposit(42, Asset.QUOTE, 1, 0));
        assertEquals(Long.MAX_VALUE, ledger.cashOf(42));
    }

    @Test
    void reserveTakesFromBalanceAndReleaseRestoresIt() {
        ledger.deposit(42, Asset.QUOTE, 1000, 0);
        assertTrue(ledger.reserveCash(42, 400));
        assertEquals(600, ledger.cashOf(42));
        ledger.releaseCash(42, 400);
        assertEquals(1000, ledger.cashOf(42));
    }

    @Test
    void reserveBeyondBalanceFailsWithoutChanges() {
        ledger.deposit(42, Asset.QUOTE, 1000, 0);
        assertFalse(ledger.reserveCash(42, 1001));
        assertEquals(1000, ledger.cashOf(42));
    }

    @Test
    void reserveForUnknownUserFails() {
        assertFalse(ledger.reserveCash(42, 1));
        assertFalse(ledger.reserveAsset(42, 1));
    }

    @Test
    void negativeReserveFailsWithoutCrediting() {
        ledger.deposit(42, Asset.QUOTE, 1000, 0);
        ledger.deposit(42, Asset.BASE, 1000, 0);
        assertFalse(ledger.reserveCash(42, -5));
        assertFalse(ledger.reserveAsset(42, -5));
        assertEquals(1000, ledger.cashOf(42));
        assertEquals(1000, ledger.assetOf(42));
    }

    @Test
    void reserveAssetTakesFromAssetOnly() {
        ledger.deposit(42, Asset.QUOTE, 1000, 0);
        ledger.deposit(42, Asset.BASE, 10, 0);
        assertTrue(ledger.reserveAsset(42, 4));
        assertEquals(6, ledger.assetOf(42));
        assertEquals(1000, ledger.cashOf(42));
    }

    @Test
    void settleCreditsReceivingLegsOnly() {
        ledger.deposit(1, Asset.QUOTE, 1000, 0);
        ledger.deposit(2, Asset.BASE, 10, 0);
        assertTrue(ledger.reserveCash(1, 500));
        assertTrue(ledger.reserveAsset(2, 5));

        ledger.settle(1, 2, 100, 5);

        assertEquals(500, ledger.cashOf(1));
        assertEquals(5, ledger.assetOf(1));
        assertEquals(500, ledger.cashOf(2));
        assertEquals(5, ledger.assetOf(2));
    }

    @Test
    void selfTradeSettleNetsToStart() {
        ledger.deposit(42, Asset.QUOTE, 1000, 0);
        ledger.deposit(42, Asset.BASE, 10, 0);
        assertTrue(ledger.reserveCash(42, 500));
        assertTrue(ledger.reserveAsset(42, 5));

        ledger.settle(42, 42, 100, 5);

        assertEquals(1000, ledger.cashOf(42));
        assertEquals(10, ledger.assetOf(42));
    }

    @Test
    void orderRejectedCarriesLedgerSeq() {
        ledger.deposit(42, Asset.QUOTE, 1000, 0);
        assertEquals(new OrderRejected(2, 0, 42, RejectReason.NSF), ledger.orderRejected(42, 0));
    }
}
