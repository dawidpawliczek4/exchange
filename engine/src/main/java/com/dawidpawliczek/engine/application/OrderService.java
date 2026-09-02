package com.dawidpawliczek.engine.application;

import com.dawidpawliczek.contracts.Asset;
import com.dawidpawliczek.contracts.Side;
import com.dawidpawliczek.contracts.Trade;
import com.dawidpawliczek.contracts.command.CancelOrderCommand;
import com.dawidpawliczek.contracts.command.DepositCommand;
import com.dawidpawliczek.contracts.command.OrderCommand;
import com.dawidpawliczek.contracts.command.PlaceOrderCommand;
import com.dawidpawliczek.contracts.event.AccountEvent;
import com.dawidpawliczek.contracts.event.MarketEvent;
import com.dawidpawliczek.contracts.event.TradeEvent;
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
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

record Job(OrderCommand cmd, long sourceOffset, CompletableFuture<Void> result) {}

record Applied(List<MarketEvent> marketEvents, List<AccountEvent> accountEvents) {}

public final class OrderService {

    private final OrderBook orderBook = new OrderBook(); // single-writer
    private final Ledger ledger = new Ledger();
    private final AtomicLong counter = new AtomicLong(0);
    private final CommandLog commandLog;
    private final MarketFeedSink marketFeedSink;
    private final AccountFeedSink accountFeedSink;
    private final LongSupplier clock;

    private final BlockingQueue<Job> queue = new ArrayBlockingQueue<>(1 << 16);
    private final Thread writerThread;
    private volatile boolean running = true;
    private volatile long sourceWatermark = -1;

    private record Staged(Job job, Order order, long timestamp) {}

    public static final class EngineOverloadedException extends RuntimeException {
        public EngineOverloadedException() {
            super();
        }
    }

    public OrderService(CommandLog commandLog, MarketFeedSink marketFeedSink, AccountFeedSink accountFeedSink) {
        this(commandLog, marketFeedSink, accountFeedSink, System::currentTimeMillis, 0, 0);
    }

    public OrderService(
            CommandLog commandLog,
            MarketFeedSink marketFeedSink,
            AccountFeedSink accountFeedSink,
            LongSupplier clock,
            long publishedMarketSeq,
            long publishedAccountSeq) {
        this.commandLog = commandLog;
        this.marketFeedSink = marketFeedSink;
        this.accountFeedSink = accountFeedSink;
        this.clock = clock;
        recover(publishedMarketSeq, publishedAccountSeq);
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

        List<Staged> staged = new ArrayList<>(batch.size());

        for (Job job : batch) {
            if (job.sourceOffset() <= maxOffset) {
                job.result().complete(null);
                continue;
            }

            long timestamp = clock.getAsLong();
            switch (job.cmd()) {
                case CancelOrderCommand c -> {
                    commandLog.append(WalCodec.encodeCancel(c.id(), c.userId(), job.sourceOffset(), timestamp));
                    staged.add(new Staged(job, null, timestamp));
                }
                case PlaceOrderCommand c -> {
                    Order order = new Order(
                            counter.getAndIncrement(), c.userId(), c.side(), c.price(), c.market(), c.quantity());
                    commandLog.append(WalCodec.encode(order, job.sourceOffset(), timestamp));
                    staged.add(new Staged(job, order, timestamp));
                }
                case DepositCommand c -> {
                    commandLog.append(
                            WalCodec.encodeDeposit(c.userId(), c.asset(), c.quantity(), job.sourceOffset(), timestamp));
                    staged.add(new Staged(job, null, timestamp));
                }
            }
            maxOffset = job.sourceOffset();
        }

        commandLog.sync();
        sourceWatermark = maxOffset;

        for (Staged s : staged) {
            Applied applied =
                    switch (s.job().cmd()) {
                        case CancelOrderCommand c -> applyCancel(c.id(), c.userId(), s.timestamp());
                        case PlaceOrderCommand _ -> applyPlace(s.order(), s.timestamp());
                        case DepositCommand c -> applyDeposit(c.userId(), c.asset(), c.quantity(), s.timestamp());
                    };

            marketFeedSink.publish(applied.marketEvents());
            accountFeedSink.publish(applied.accountEvents());
            s.job().result().complete(null);
        }
    }

    private Applied applyPlace(Order order, long timestamp) {
        boolean reserved;
        if (order.quantity() <= 0 || (!order.isMarket() && order.price() <= 0)) {
            reserved = false;
        } else if (order.side() == Side.BUY) {
            long cost = order.isMarket()
                    ? orderBook.calculateMarketOrderValue(order.quantity())
                    : limitCost(order.price(), order.quantity());
            reserved = cost >= 0 && ledger.reserveCash(order.userId(), cost);
        } else {
            reserved = ledger.reserveAsset(order.userId(), order.quantity());
        }
        if (!reserved) {
            return new Applied(List.of(), List.of(ledger.orderRejected(order.userId(), timestamp)));
        }

        List<MarketEvent> events = orderBook.submit(order, timestamp);
        for (MarketEvent event : events) {
            if (!(event instanceof TradeEvent tradeEvent)) continue;
            Trade trade = tradeEvent.trade();
            long buyerId = order.side() == Side.BUY ? trade.takerUserId() : trade.makerUserId();
            long sellerId = order.side() == Side.BUY ? trade.makerUserId() : trade.takerUserId();
            ledger.settle(buyerId, sellerId, trade.price(), trade.quantity());
            if (order.side() == Side.BUY && !order.isMarket() && order.price() > trade.price()) {
                ledger.releaseCash(order.userId(), (order.price() - trade.price()) * trade.quantity());
            }
        }
        if (order.isMarket() && order.side() == Side.SELL && order.quantity() > 0) {
            ledger.releaseAsset(order.userId(), order.quantity());
        }
        return new Applied(events, List.of());
    }

    private Applied applyCancel(long orderId, long userId, long timestamp) {
        var result = orderBook.cancel(orderId, userId, timestamp);
        Order cancelled = result.cancelled();
        if (cancelled != null) {
            if (cancelled.side() == Side.BUY) {
                ledger.releaseCash(cancelled.userId(), cancelled.price() * cancelled.quantity());
            } else {
                ledger.releaseAsset(cancelled.userId(), cancelled.quantity());
            }
        }
        return new Applied(List.of(result.event()), List.of());
    }

    private Applied applyDeposit(long userId, Asset asset, long quantity, long timestamp) {
        return new Applied(List.of(), List.of(ledger.deposit(userId, asset, quantity, timestamp)));
    }

    private static long limitCost(long price, long quantity) {
        try {
            return Math.multiplyExact(price, quantity);
        } catch (ArithmeticException e) {
            return -1;
        }
    }

    Ledger ledger() {
        return ledger;
    }

    private void recover(long publishedMarketSeq, long publishedAccountSeq) {
        commandLog.replay(payload -> {
            var record = WalCodec.decode(payload);
            Applied applied =
                    switch (record) {
                        case PlaceRecord r -> {
                            counter.set(r.order().id() + 1);
                            yield applyPlace(r.order(), r.timestamp());
                        }
                        case CancelRecord r -> applyCancel(r.orderId(), r.userId(), r.timestamp());
                        case DepositRecord r -> applyDeposit(r.userId(), r.asset(), r.quantity(), r.timestamp());
                    };
            sourceWatermark = Math.max(sourceWatermark, record.sourceOffset());

            List<MarketEvent> market = applied.marketEvents().stream()
                    .filter(e -> e.seq() > publishedMarketSeq)
                    .toList();
            if (!market.isEmpty()) marketFeedSink.publish(market);
            List<AccountEvent> account = applied.accountEvents().stream()
                    .filter(e -> e.seq() > publishedAccountSeq)
                    .toList();
            if (!account.isEmpty()) accountFeedSink.publish(account);
        });
    }
}
