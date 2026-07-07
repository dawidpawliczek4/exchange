package com.dawidpawliczek.engine.domain;

import com.dawidpawliczek.contracts.*;
import java.util.*;
import java.util.function.LongSupplier;

public final class OrderBook {

    private final NavigableMap<Long, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, Deque<Order>> asks = new TreeMap<>();

    private final LongSupplier clock;
    private long seq = 0;

    public OrderBook() {
        this(System::currentTimeMillis);
    }

    public OrderBook(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized MarketEvent cancel(long orderId, long userId) {
        boolean removed = cancelIn(orderId, userId, bids) || cancelIn(orderId, userId, asks);
        CancelStatus status = removed ? CancelStatus.CANCELED : CancelStatus.REJECTED;
        return new CancelEvent(++seq, clock.getAsLong(), userId, orderId, status);
    }

    private boolean cancelIn(long orderId, long userId, NavigableMap<Long, Deque<Order>> side) {
        for (var e : side.entrySet()) {
            var orders = e.getValue();
            var it = orders.iterator();
            while (it.hasNext()) {
                var o = it.next();
                if (o.id() == orderId && o.userId() == userId) {
                    it.remove();
                    if (orders.isEmpty()) side.remove(e.getKey());
                    return true;
                }
            }
        }
        return false;
    }

    public synchronized List<MarketEvent> submit(Order incoming) {

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
                events.add(new TradeEvent(++seq, clock.getAsLong(), trade));

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
