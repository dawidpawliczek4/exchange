package com.dawidpawliczek.engine.application;

import com.dawidpawliczek.engine.domain.Order;
import com.dawidpawliczek.engine.domain.OrderBook;
import com.dawidpawliczek.engine.domain.Side;
import com.dawidpawliczek.engine.domain.Trade;
import com.dawidpawliczek.engine.ports.CommandLog;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;


public final class OrderService {

    // payload: id(8) + userId(8) + side(1) + price(8) + market(1) + quantity(8)
    private static final int PAYLOAD_SIZE = 8 + 8 + 1 + 8 + 1 + 8;

    private final OrderBook orderBook = new OrderBook();                       // single-writer
    private final AtomicLong counter = new AtomicLong(0);
    private final CopyOnWriteArrayList<Trade> transactionHistory = new CopyOnWriteArrayList<>();
    private final CommandLog commandLog;

    public OrderService(CommandLog commandLog) {
        this.commandLog = commandLog;
        recover();
    }

    public synchronized List<Trade> place(PlaceOrderCommand cmd) {
        Order order = new Order(
                counter.getAndIncrement(),
                cmd.userId(),
                cmd.side(),
                cmd.price(),
                cmd.market(),
                cmd.quantity()
        );
        commandLog.append(serialize(order));
        List<Trade> trades = orderBook.submit(order);
        transactionHistory.addAll(trades);
        return trades;
    }

    public List<Trade> history() {
        return transactionHistory;
    }

    private void recover() {
        commandLog.replay(payload -> {
            Order order = deserialize(payload);
            transactionHistory.addAll(orderBook.submit(order));
            counter.set(order.id() + 1);
        });
    }


    private static byte[] serialize(Order o) {
        ByteBuffer b = ByteBuffer.allocate(PAYLOAD_SIZE);
        b.putLong(o.id());
        b.putLong(o.userId());
        b.put((byte) (o.side() == Side.SELL ? 0 : 1));
        b.putLong(o.price());
        b.put((byte) (o.isMarket() ? 1 : 0));
        b.putLong(o.quantity());
        return b.array();
    }

    private static Order deserialize(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        long id = b.getLong();
        long userId = b.getLong();
        Side side = b.get() == 0 ? Side.SELL : Side.BUY;
        long price = b.getLong();
        boolean market = b.get() == 1;
        long quantity = b.getLong();
        return new Order(id, userId, side, price, market, quantity);
    }
}
