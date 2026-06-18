package com.dawidpawliczek.benchmark;

import com.dawidpawliczek.engine.application.PlaceOrderCommand;
import com.dawidpawliczek.engine.domain.Order;
import com.dawidpawliczek.engine.domain.OrderBook;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class OrderBookBenchmark {

    static final int BATCH = 50_000;

    private PlaceOrderCommand[] workload;
    private OrderBook book;
    private long idSeq;

    @Setup(Level.Trial)
    public void genWorkload() {
        workload = Workload.generate(BATCH, 42L);
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
            bh.consume(book.submit(order));
        }
    }
}
