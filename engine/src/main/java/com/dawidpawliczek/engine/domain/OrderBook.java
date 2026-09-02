package com.dawidpawliczek.engine.domain;

import com.dawidpawliczek.contracts.*;
import com.dawidpawliczek.contracts.event.CancelEvent;
import com.dawidpawliczek.contracts.event.MarketEvent;
import com.dawidpawliczek.contracts.event.TradeEvent;
import java.util.*;

public final class OrderBook {

    private final NavigableMap<Long, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, Deque<Order>> asks = new TreeMap<>();

    private long seq = 0;

    public record CancelResult(CancelEvent event, Order cancelled) {}

    public synchronized CancelResult cancel(long orderId, long userId, long timestamp) {
        Order removed = cancelIn(orderId, userId, bids);
        if (removed == null) removed = cancelIn(orderId, userId, asks);
        CancelStatus status = removed != null ? CancelStatus.CANCELED : CancelStatus.REJECTED;
        return new CancelResult(new CancelEvent(++seq, timestamp, userId, orderId, status), removed);
    }

    private Order cancelIn(long orderId, long userId, NavigableMap<Long, Deque<Order>> side) {
        for (var e : side.entrySet()) {
            var orders = e.getValue();
            var it = orders.iterator();
            while (it.hasNext()) {
                var o = it.next();
                if (o.id() == orderId && o.userId() == userId) {
                    it.remove();
                    if (orders.isEmpty()) side.remove(e.getKey());
                    return o;
                }
            }
        }
        return null;
    }

    public synchronized long calculateMarketOrderValue(long quantity) {
        long cost = 0;
        long remaining = quantity;
        for (var level : asks.entrySet()) {
            long price = level.getKey();
            for (Order resting : level.getValue()) {
                if (remaining <= 0) return cost;
                long fill = Math.min(remaining, resting.quantity());
                try {
                    cost = Math.addExact(cost, Math.multiplyExact(fill, price));
                } catch (ArithmeticException e) {
                    return -1;
                }
                remaining -= fill;
            }
        }
        return cost;
    }

    public synchronized List<MarketEvent> submit(Order incoming, long timestamp) {

        List<MarketEvent> events = new ArrayList<>();

        NavigableMap<Long, Deque<Order>> opposite = (incoming.side() == Side.BUY) ? asks : bids;

        while (incoming.quantity() > 0 && !opposite.isEmpty()) {

            var bestQueue = opposite.firstEntry();
            Order maker = bestQueue.getValue().peekFirst();

            if (isMatch(incoming, maker)) {
                long restingPrice = bestQueue.getKey();

                long filled = Math.min(incoming.quantity(), maker.quantity());
                Trade trade =
                        new Trade(maker.id(), maker.userId(), incoming.id(), incoming.userId(), restingPrice, filled);
                events.add(new TradeEvent(++seq, timestamp, trade));

                incoming.reduce(filled);
                maker.reduce(filled);

                if (maker.quantity() == 0) bestQueue.getValue().pollFirst();
                if (bestQueue.getValue().isEmpty()) opposite.remove(restingPrice);
            } else {
                break;
            }
        }

        if (incoming.quantity() > 0 && !incoming.isMarket()) {
            var ourSide = (incoming.side() == Side.BUY) ? bids : asks;
            ourSide.computeIfAbsent(incoming.price(), _ -> new ArrayDeque<>()).add(incoming);
        }

        return events;
    }

    private boolean isMatch(Order incoming, Order best) {
        if (incoming.isMarket()) return true;
        if (incoming.side() == Side.BUY) {
            return incoming.price() >= best.price();
        } else {
            return incoming.price() <= best.price();
        }
    }
}
