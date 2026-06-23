package com.dawidpawliczek.engine.application;

import com.dawidpawliczek.engine.domain.Order;
import com.dawidpawliczek.engine.domain.OrderBook;
import com.dawidpawliczek.engine.domain.Side;
import com.dawidpawliczek.engine.domain.Trade;
import com.dawidpawliczek.engine.ports.CommandLog;
import com.dawidpawliczek.engine.ports.MarketFeedSink;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

record Job(PlaceOrderCommand cmd, CompletableFuture<List<Trade>> result) {}

public final class OrderService {

    // payload: id(8) + userId(8) + side(1) + price(8) + market(1) + quantity(8)
    private static final int PAYLOAD_SIZE = 8 + 8 + 1 + 8 + 1 + 8;

    private final OrderBook orderBook = new OrderBook(); // single-writer
    private final AtomicLong counter = new AtomicLong(0);
    private final Queue<Trade> transactionHistory = new ConcurrentLinkedQueue<>();
    private final CommandLog commandLog;
    private final MarketFeedSink marketFeedSink;

    private final BlockingQueue<Job> queue = new ArrayBlockingQueue<>(1 << 16);
    private final Thread writerThread;
    private volatile boolean running = true;

    public OrderService(CommandLog commandLog, MarketFeedSink marketFeedSink) {
        this.commandLog = commandLog;
        this.marketFeedSink = marketFeedSink;
        recover();
        this.writerThread = new Thread(this::writerLoop, "matching-writer");
        this.writerThread.start();
    }

    public CompletableFuture<List<Trade>> place(PlaceOrderCommand cmd) throws InterruptedException {
        if (!running) return CompletableFuture.failedFuture(new IllegalStateException("engine stopped"));
        CompletableFuture<List<Trade>> box = new CompletableFuture<>();
        queue.put(new Job(cmd, box));
        return box;
    }

    void writerLoop() {
        List<Job> batch = new ArrayList<>(1024);
        while (running) {
            try {
                batch.clear();
                batch.add(queue.take());
                queue.drainTo(batch);
                List<Order> orders = new ArrayList<>(1024);
                for (Job job : batch) {
                    Order order = new Order(
                            counter.getAndIncrement(),
                            job.cmd().userId(),
                            job.cmd().side(),
                            job.cmd().price(),
                            job.cmd().market(),
                            job.cmd().quantity());
                    orders.add(order);
                    commandLog.append(serialize(order));
                }

                commandLog.sync();

                for (int i = 0; i < orders.size(); i++) {
                    Order order = orders.get(i);
                    Job job = batch.get(i);
                    List<Trade> trades = orderBook.submit(order);
                    transactionHistory.addAll(trades);
                    job.result().complete(trades);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                running = false;
                for (Job j : batch) j.result().completeExceptionally(e);

                List<Job> leftover = new ArrayList<>();
                queue.drainTo(leftover);
                for (Job j : leftover) j.result().completeExceptionally(e);
            }
        }
    }

    public List<Trade> history() {
        return List.copyOf(transactionHistory);
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
