package com.dawidpawliczek.benchmark;

import com.dawidpawliczek.contracts.PlaceOrderCommand;
import com.dawidpawliczek.contracts.Side;
import java.util.Random;

public final class Workload {

    private Workload() {}

    /**
     * @param n    number of orders to generate
     * @param seed RNG seed (fix it for reproducibility)
     * @return deterministic array of place-order commands
     */
    public static PlaceOrderCommand[] generate(int n, long seed) {
        Random rnd = new Random(seed);
        PlaceOrderCommand[] cmds = new PlaceOrderCommand[n];

        long mid = 10_000; // price level orders cluster around
        int spread = 10; // prices land in [mid-spread, mid+spread]

        for (int i = 0; i < n; i++) {
            Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
            long price = mid + (rnd.nextInt(2 * spread + 1) - spread);
            long qty = 1 + rnd.nextInt(10);
            long userId = i % 100;
            cmds[i] = new PlaceOrderCommand(userId, side, price, false, qty);
        }
        return cmds;
    }
}
