package com.dawidpawliczek.engine.domain;

import com.dawidpawliczek.contracts.Asset;
import com.dawidpawliczek.contracts.RejectReason;
import com.dawidpawliczek.contracts.event.AccountEvent;
import com.dawidpawliczek.contracts.event.DepositAccepted;
import com.dawidpawliczek.contracts.event.DepositRejected;
import com.dawidpawliczek.contracts.event.OrderRejected;
import java.util.HashMap;
import java.util.function.LongSupplier;

public class Ledger {

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

    public AccountEvent deposit(long userId, Asset asset, long quantity) {
        if (quantity > 0 && canAdd(quantity, balanceOf(userId, asset))) {
            Wallet wallet = walletHashMap.computeIfAbsent(userId, _ -> new Wallet());
            if (asset == Asset.QUOTE) {
                wallet.cash += quantity;
            } else {
                wallet.asset += quantity;
            }
            return new DepositAccepted(++seq, clock.getAsLong(), userId, asset);
        } else {
            return new DepositRejected(++seq, clock.getAsLong(), userId, asset);
        }
    }

    static boolean canAdd(long a, long b) {
        return b <= 0 ? Long.MIN_VALUE - b <= a : Long.MAX_VALUE - b >= a;
    }

    public long cashOf(long userId) {
        Wallet wallet = walletHashMap.get(userId);
        return wallet == null ? 0 : wallet.cash;
    }

    public long assetOf(long userId) {
        Wallet wallet = walletHashMap.get(userId);
        return wallet == null ? 0 : wallet.asset;
    }

    private long balanceOf(long userId, Asset asset) {
        return asset == Asset.QUOTE ? cashOf(userId) : assetOf(userId);
    }

    public boolean reserveCash(long userId, long quantity) {
        var user = walletHashMap.get(userId);

        if (quantity < 0 || user == null || user.cash < quantity) {
            return false;
        }

        user.cash -= quantity;
        return true;
    }

    public boolean reserveAsset(long userId, long quantity) {
        var user = walletHashMap.get(userId);

        if (quantity < 0 || user == null || user.asset < quantity) {
            return false;
        }

        user.asset -= quantity;
        return true;
    }

    public void releaseAsset(long userId, long quantity) {
        // if we release - we canceled an order. we can be sure that user exists in hashmap, because to cancel an order,
        // it must first be placed, which required reserve.
        walletHashMap.get(userId).asset += quantity;
    }

    public void releaseCash(long userId, long quantity) {
        // if we release - we canceled an order. we can be sure that user exists in hashmap, because to cancel an order,
        // it must first be placed, which required reserve.
        walletHashMap.get(userId).cash += quantity;
    }

    // paying legs were already taken at reserve time, so settle only credits the receiving legs
    public void settle(long buyerId, long sellerId, long price, long quantity) {
        walletHashMap.get(buyerId).asset += quantity;
        walletHashMap.get(sellerId).cash += price * quantity;
    }

    public OrderRejected orderRejected(long userId) {
        return new OrderRejected(++seq, clock.getAsLong(), userId, RejectReason.NSF);
    }
}
