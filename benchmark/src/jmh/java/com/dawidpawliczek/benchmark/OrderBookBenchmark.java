package com.dawidpawliczek.benchmark;

import com.dawidpawliczek.contracts.command.PlaceOrderCommand;
import com.dawidpawliczek.engine.domain.Order;
import com.dawidpawliczek.engine.domain.OrderBook;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode({Mode.Throughput})
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 20, time = 1)
@Fork(
        value = 10,
        jvmArgs = {"-Xms2g", "-Xmx2g", "-XX:+AlwaysPreTouch"})
@State(Scope.Thread)
public class OrderBookBenchmark {

    static final int BATCH = 50_000;

    private PlaceOrderCommand[] workload;
    private OrderBook book;
    private long idSeq;

    @Param({"10", "100", "1000", "10000"})
    int spread;

    @Setup(Level.Trial)
    public void genWorkload() {
        workload = Workload.generate(BATCH, spread, 42L);
    }

    @Setup(Level.Invocation)
    public void freshBook() {
        book = new OrderBook();
        idSeq = 0;
    }

    @Benchmark
    @OperationsPerInvocation(BATCH)
    public void matchBatch(Blackhole bh) {
        for (PlaceOrderCommand c : workload) {
            Order order = new Order(idSeq++, c.userId(), c.side(), c.price(), c.market(), c.quantity());
            bh.consume(book.submit(order, 0));
        }
    }
}
