package com.dawidpawliczek.engine.application;

import com.dawidpawliczek.contracts.command.CancelOrderCommand;
import com.dawidpawliczek.contracts.command.DepositCommand;
import com.dawidpawliczek.contracts.command.OrderCommand;
import com.dawidpawliczek.contracts.command.PlaceOrderCommand;
import com.dawidpawliczek.contracts.event.AccountEvent;
import com.dawidpawliczek.contracts.event.MarketEvent;
import com.dawidpawliczek.engine.domain.Ledger;
import com.dawidpawliczek.engine.domain.Order;
import com.dawidpawliczek.engine.domain.OrderBook;
import com.dawidpawliczek.engine.ports.AccountFeedSink;
import com.dawidpawliczek.engine.ports.CommandLog;
import com.dawidpawliczek.engine.ports.MarketFeedSink;
import com.dawidpawliczek.engine.wire.CancelRecord;
import com.dawidpawliczek.engine.wire.DepositRecord;
import com.dawidpawliczek.engine.wire.PlaceRecord;
import com.dawidpawliczek.engine.wire.WalCodec;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

record Job(OrderCommand cmd, long sourceOffset, CompletableFuture<Void> result) {}

public final class OrderService {

    private final OrderBook orderBook = new OrderBook(); // single-writer
    private final Ledger ledger = new Ledger();
    private final AtomicLong counter = new AtomicLong(0);
    private final CommandLog commandLog;
    private final MarketFeedSink marketFeedSink;
    private final AccountFeedSink accountFeedSink;

    private final BlockingQueue<Job> queue = new ArrayBlockingQueue<>(1 << 16);
    private final Thread writerThread;
    private volatile boolean running = true;
    private volatile long sourceWatermark = -1;

    public static final class EngineOverloadedException extends RuntimeException {
        public EngineOverloadedException() {
            super();
        }
    }

    public OrderService(CommandLog commandLog, MarketFeedSink marketFeedSink, AccountFeedSink accountFeedSink) {
        this.commandLog = commandLog;
        this.marketFeedSink = marketFeedSink;
        this.accountFeedSink = accountFeedSink;
        recover();
        this.writerThread = new Thread(this::writerLoop, "matching-writer");
        this.writerThread.start();
    }

    public CompletableFuture<Void> submit(OrderCommand cmd, long sourceOffset) throws InterruptedException {
        if (!running) return CompletableFuture.failedFuture(new IllegalStateException("engine stopped"));
        CompletableFuture<Void> box = new CompletableFuture<>();
        queue.put(new Job(cmd, sourceOffset, box));
        return box;
    }

    public CompletableFuture<Void> submitOffer(OrderCommand cmd, long sourceOffset) throws InterruptedException {
        if (!running) return CompletableFuture.failedFuture(new IllegalStateException("engine stopped"));
        CompletableFuture<Void> box = new CompletableFuture<>();
        if (!queue.offer(new Job(cmd, sourceOffset, box))) {
            return CompletableFuture.failedFuture(new EngineOverloadedException());
        }
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

                processBatch(batch);

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

    void processBatch(List<Job> batch) {
        long maxOffset = sourceWatermark;

        List<Job> applyJobs = new ArrayList<>(batch.size());
        List<Order> applyOrders = new ArrayList<>(batch.size());

        for (Job job : batch) {
            if (job.sourceOffset() <= maxOffset) {
                job.result().complete(null);
                continue;
            }

            switch (job.cmd()) {
                case CancelOrderCommand c -> {
                    commandLog.append(WalCodec.encodeCancel(c.id(), c.userId(), job.sourceOffset()));
                    applyJobs.add(job);
                }
                case PlaceOrderCommand c -> {
                    Order order = new Order(
                            counter.getAndIncrement(), c.userId(), c.side(), c.price(), c.market(), c.quantity());
                    commandLog.append(WalCodec.encode(order, job.sourceOffset()));
                    applyJobs.add(job);
                    applyOrders.add(order);
                }
                case DepositCommand c -> {
                    commandLog.append(WalCodec.encodeDeposit(c.userId(), c.quantity(), job.sourceOffset()));
                    applyJobs.add(job);
                }
            }
            maxOffset = job.sourceOffset();
        }

        commandLog.sync();
        sourceWatermark = maxOffset;

        Iterator<Order> applyOrdersIterator = applyOrders.iterator();

        for (int i = 0; i < applyJobs.size(); i++) {

            Job job = applyJobs.get(i);
            List<MarketEvent> marketEvents = List.of();
            List<AccountEvent> accountEvents = List.of();

            switch (job.cmd()) {
                case CancelOrderCommand c -> {
                    marketEvents = List.of(orderBook.cancel(c.id(), c.userId()));
                }
                case PlaceOrderCommand c -> {
                    Order order = applyOrdersIterator.next();
                    marketEvents = orderBook.submit(order);
                }
                case DepositCommand c -> {
                    accountEvents = List.of(ledger.deposit(c.userId(), c.quantity()));
                }
            }

            marketFeedSink.publish(marketEvents);
            accountFeedSink.publish(accountEvents);
            job.result().complete(null);
        }
    }

    private void recover() {
        commandLog.replay(payload -> {
            var record = WalCodec.decode(payload);
            switch (record) {
                case PlaceRecord r -> {
                    orderBook.submit(r.order());
                    counter.set(r.order().id() + 1);
                }
                case CancelRecord r -> {
                    orderBook.cancel(r.orderId(), r.userId());
                }
                case DepositRecord r -> {
                    ledger.deposit(r.userId(), r.quantity());
                }
            }
            sourceWatermark = Math.max(sourceWatermark, record.sourceOffset());
        });
    }
}
