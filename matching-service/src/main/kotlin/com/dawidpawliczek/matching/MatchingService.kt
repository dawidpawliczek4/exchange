package com.dawidpawliczek.matching

import java.nio.file.Path

fun main() {
    val runner =
        MatchingRunner(
            bootstrapServers = System.getenv("KAFKA_BOOTSTRAP_SERVERS") ?: "localhost:9092",
            journalPath = Path.of("journal.bin"),
            heartbeatPath = Path.of("/tmp/alive"),
        )
    Runtime.getRuntime().addShutdownHook(Thread { runner.close() })
    runner.start()
    runner.awaitTermination()
}
