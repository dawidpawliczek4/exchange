package com.dawidpawliczek.engine;

import java.util.*;

public final class OrderBook {

    private final NavigableMap<Long, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());

    private final NavigableMap<Long, Deque<Order>> asks = new TreeMap<>();

    public List<Trade> submit(Order incoming) {
        List<Trade> trades = new ArrayList<>();

        NavigableMap<Long, Deque<Order>> opposite = (incoming.side() == Side.BUY) ? asks : bids;

        while (incoming.quantity() > 0 && !opposite.isEmpty()) {

            var bestQueue = opposite.firstEntry();
            Order maker = bestQueue.getValue().peekFirst();

            if (isMatch(incoming, maker)) {
                long restingPrice = bestQueue.getKey();

                long filled = Math.min(incoming.quantity(), maker.quantity());
                trades.add(new Trade(maker.id(), incoming.id(), restingPrice, filled));

                incoming.reduce(filled);
                maker.reduce(filled);

                if (maker.quantity() == 0) bestQueue.getValue().pollFirst();
                if (bestQueue.getValue().isEmpty()) asks.remove(restingPrice);
            }
        }

        return trades;
    }

    private boolean isMatch(Order incoming, Order best) {
        if (incoming.side() == Side.BUY) {
            return incoming.price() >= best.price();
        } else {
            return incoming.price() <= best.price();
        }
    }

}
