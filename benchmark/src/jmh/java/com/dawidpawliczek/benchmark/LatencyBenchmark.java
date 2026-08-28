package com.dawidpawliczek.benchmark;

import com.dawidpawliczek.contracts.command.PlaceOrderCommand;
import com.dawidpawliczek.engine.application.OrderService;
import com.dawidpawliczek.engine.ports.CommandLog;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogWriter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

@BenchmarkMode(Mode.SingleShotTime)
@Fork(10)
@Measurement(iterations = 5)
@Warmup(iterations = 3)
@State(Scope.Benchmark)
public class LatencyBenchmark {

    private static final Path RESULTS_DIR =
            Paths.get(System.getProperty("results.dir", "results")).toAbsolutePath();

    static final long EXECUTION_TIME_NS = 5_000_000_000L;

    @Param({"10000000", "1000000", "100000", "10000"})
    long periodNs;

    int iterationNo = 0;
    OrderService orderService;
    Histogram histogram;
    PlaceOrderCommand[] cmds;
    long iterationStartMillis;

    @Setup(Level.Iteration)
    public void setUp() {
        CommandLog commandLog = new InMemoryCommandLog();
        orderService = new OrderService(commandLog, events -> {});
        histogram = new ConcurrentHistogram(3);
        cmds = Workload.generate((int) (EXECUTION_TIME_NS / periodNs), 100, 42L);
        iterationStartMillis = System.currentTimeMillis();

        iterationNo++;
    }

    @TearDown(Level.Iteration)
    public void tearDown(IterationParams iterationParams) throws IOException {
        orderService.close();

        if (iterationParams.getType() == IterationType.WARMUP) {
            return;
        }

        Files.createDirectories(RESULTS_DIR);
        Path file = RESULTS_DIR.resolve(String.format(
                "latency-p%d-f%d-i%02d.hlog", periodNs, ProcessHandle.current().pid(), iterationNo));
        try (PrintStream out = new PrintStream(Files.newOutputStream(file), false, StandardCharsets.UTF_8)) {
            HistogramLogWriter writer = new HistogramLogWriter(out);
            writer.outputLogFormatVersion();
            writer.outputStartTime(iterationStartMillis);
            writer.outputLegend();
            histogram.setStartTimeStamp(iterationStartMillis);
            histogram.setEndTimeStamp(System.currentTimeMillis());
            writer.outputIntervalHistogram(histogram);
        }
    }

    @Benchmark
    public void run() throws InterruptedException {

        var ticks = (int) (EXECUTION_TIME_NS / periodNs);
        CompletableFuture<?>[] futures = new CompletableFuture<?>[ticks];

        long t0 = System.nanoTime();
        for (int i = 0; i < ticks; i++) {
            long intendedStart = t0 + i * periodNs;
            while (System.nanoTime() < intendedStart) {
                Thread.onSpinWait();
            }
            futures[i] = orderService
                    .submit(cmds[i], i)
                    .whenComplete((events, ex) -> histogram.recordValue(System.nanoTime() - intendedStart));
        }

        CompletableFuture.allOf(futures).join();
    }
}
