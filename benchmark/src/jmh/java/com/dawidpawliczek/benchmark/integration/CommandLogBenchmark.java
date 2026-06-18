package com.dawidpawliczek.benchmark.integration;

import com.dawidpawliczek.app.ExchangeApplication;
import com.dawidpawliczek.app.order.OrderController;
import com.dawidpawliczek.app.order.OrderRequest;
import com.dawidpawliczek.benchmark.Workload;
import com.dawidpawliczek.engine.application.PlaceOrderCommand;
import com.dawidpawliczek.engine.domain.Trade;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(4)
@State(Scope.Benchmark)
public class CommandLogBenchmark {

    static final int WORKLOAD_SIZE = 50_000;

    @Param({"memory", "file"})
    public String backend;

    private ConfigurableApplicationContext ctx;
    private OrderController controller;
    private OrderRequest[] workload;
    private final AtomicInteger idx = new AtomicInteger();

    @Setup(Level.Trial)
    public void boot() {
        ctx = new SpringApplicationBuilder(ExchangeApplication.class)
                .properties("spring.profiles.active=benchmark", "exchange.commandlog=" + backend)
                .web(WebApplicationType.NONE)
                .run();
        controller = ctx.getBean(OrderController.class);

        PlaceOrderCommand[] cmds = Workload.generate(WORKLOAD_SIZE, 42L);
        workload = new OrderRequest[cmds.length];
        for (int i = 0; i < cmds.length; i++) {
            PlaceOrderCommand c = cmds[i];
            workload[i] = new OrderRequest(c.userId(), c.side(), c.price(), c.market(), c.quantity());
        }
        idx.set(0);
    }

    @TearDown(Level.Trial)
    public void shutdown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    @Benchmark
    public List<Trade> postOrder() {
        OrderRequest req = workload[idx.getAndIncrement() % workload.length];
        return controller.postOrder(req);
    }
}
