package com.dawidpawliczek.engine.application;

import com.dawidpawliczek.contracts.MarketEvent;
import com.dawidpawliczek.contracts.PlaceOrderCommand;
import com.dawidpawliczek.engine.domain.Order;
import com.dawidpawliczek.engine.domain.OrderBook;
import com.dawidpawliczek.engine.ports.CommandLog;
import com.dawidpawliczek.engine.ports.MarketFeedSink;
import com.dawidpawliczek.engine.wire.WalCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

record Job(PlaceOrderCommand cmd, long sourceOffset, CompletableFuture<List<MarketEvent>> result) {}

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

    public CompletableFuture<List<MarketEvent>> place(PlaceOrderCommand cmd, long sourceOffset)
            throws InterruptedException {
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
                batch.clear();
                batch.add(queue.take());
                queue.drainTo(batch);
                List<Job> fresh = new ArrayList<>(batch.size());
                List<Order> orders = new ArrayList<>(batch.size());
                long maxOffset = sourceWatermark;
                for (Job job : batch) {
                    if (job.sourceOffset() <= maxOffset) {
                        job.result().complete(List.of());
                        continue;
                    }
                    Order order = new Order(
                            counter.getAndIncrement(),
                            job.cmd().userId(),
                            job.cmd().side(),
                            job.cmd().price(),
                            job.cmd().market(),
                            job.cmd().quantity());
                    fresh.add(job);
                    orders.add(order);
                    commandLog.append(WalCodec.encode(order, job.sourceOffset()));
                    maxOffset = job.sourceOffset();
                }

                if (orders.isEmpty()) continue;

                commandLog.sync();
                sourceWatermark = maxOffset;

                for (int i = 0; i < orders.size(); i++) {
                    Order order = orders.get(i);
                    Job job = fresh.get(i);
                    List<MarketEvent> events = orderBook.submit(order);
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
            transactionHistory.addAll(orderBook.submit(record.order()));
            counter.set(record.order().id() + 1);
            sourceWatermark = Math.max(sourceWatermark, record.sourceOffset());
        });
    }
}
