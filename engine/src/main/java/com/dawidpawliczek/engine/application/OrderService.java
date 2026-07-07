package com.dawidpawliczek.engine.application;

import com.dawidpawliczek.contracts.CancelOrderCommand;
import com.dawidpawliczek.contracts.MarketEvent;
import com.dawidpawliczek.contracts.OrderCommand;
import com.dawidpawliczek.contracts.PlaceOrderCommand;
import com.dawidpawliczek.engine.domain.Order;
import com.dawidpawliczek.engine.domain.OrderBook;
import com.dawidpawliczek.engine.ports.CommandLog;
import com.dawidpawliczek.engine.ports.MarketFeedSink;
import com.dawidpawliczek.engine.wire.CancelRecord;
import com.dawidpawliczek.engine.wire.PlaceRecord;
import com.dawidpawliczek.engine.wire.WalCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

record Job(OrderCommand cmd, long sourceOffset, CompletableFuture<List<MarketEvent>> result) {}

public final class OrderService {

    private final OrderBook orderBook = new OrderBook(); // single-writer
    private final AtomicLong counter = new AtomicLong(0);
    private final Queue<MarketEvent> transactionHistory = new ConcurrentLinkedQueue<>();
    private final CommandLog commandLog;
    private final MarketFeedSink marketFeedSink;

    private final BlockingQueue<Job> queue = new ArrayBlockingQueue<>(1 << 16);
    private final Thread writerThread;
    private volatile boolean running = true;
    private volatile long sourceWatermark = -1;

    public OrderService(CommandLog commandLog, MarketFeedSink marketFeedSink) {
        this.commandLog = commandLog;
        this.marketFeedSink = marketFeedSink;
        recover();
        this.writerThread = new Thread(this::writerLoop, "matching-writer");
        this.writerThread.start();
    }

    public CompletableFuture<List<MarketEvent>> submit(OrderCommand cmd, long sourceOffset) throws InterruptedException {
        if (!running) return CompletableFuture.failedFuture(new IllegalStateException("engine stopped"));
        CompletableFuture<List<MarketEvent>> box = new CompletableFuture<>();
        queue.put(new Job(cmd, sourceOffset, box));
        return box;
    }

    public long lastSourceOffset() {
        return sourceWatermark;
    }

    public void close() {
        running = false;
        writerThread.interrupt();
        try {
            writerThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void writerLoop() {
        List<Job> batch = new ArrayList<>(1024);
        while (running) {
            try {
                long maxOffset = sourceWatermark;
                batch.clear();
                batch.add(queue.take());
                queue.drainTo(batch);
                List<Job> applyJobs = new ArrayList<>(batch.size());
                List<Order> applyOrders = new ArrayList<>(batch.size());

                for (Job job : batch) {
                    if (job.sourceOffset() <= maxOffset) {
                        job.result().complete(List.of());
                        continue;
                    }
                    switch (job.cmd()) {
                        case CancelOrderCommand c -> {
                            commandLog.append(WalCodec.encodeCancel(c.id(), c.userId(), job.sourceOffset()));
                            applyJobs.add(job);
                            applyOrders.add(null);
                        }
                        case PlaceOrderCommand c -> {
                            Order order = new Order(
                                    counter.getAndIncrement(),
                                    c.userId(),
                                    c.side(),
                                    c.price(),
                                    c.market(),
                                    c.quantity());
                            commandLog.append(WalCodec.encode(order, job.sourceOffset()));
                            applyJobs.add(job);
                            applyOrders.add(order);
                        }
                    }
                    maxOffset = job.sourceOffset();
                }

                commandLog.sync();
                sourceWatermark = maxOffset;

                for (int i = 0; i < applyJobs.size(); i++) {
                    Job job = applyJobs.get(i);
                    Order order = applyOrders.get(i);
                    List<MarketEvent> events;
                    if (order == null) {
                        CancelOrderCommand c = (CancelOrderCommand) job.cmd();
                        events = List.of(orderBook.cancel(c.id(), c.userId()));
                    } else {
                        events = orderBook.submit(order);
                    }
                    transactionHistory.addAll(events);
                    marketFeedSink.publish(events);
                    job.result().complete(events);
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

    public List<MarketEvent> history() {
        return List.copyOf(transactionHistory);
    }

    private void recover() {
        commandLog.replay(payload -> {
            var record = WalCodec.decode(payload);
            switch (record) {
                case PlaceRecord p -> {
                    transactionHistory.addAll(orderBook.submit(p.order()));
                    counter.set(p.order().id() + 1);
                }
                case CancelRecord c -> {
                    transactionHistory.add(orderBook.cancel(c.orderId(), c.userId()));
                }
            }
            sourceWatermark = Math.max(sourceWatermark, record.sourceOffset());
        });
    }
}
