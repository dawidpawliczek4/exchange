package com.dawidpawliczek.matching

import com.dawidpawliczek.contracts.MarketEvent
import com.dawidpawliczek.contracts.Topics
import com.dawidpawliczek.contracts.WireCodec
import com.dawidpawliczek.engine.application.OrderService
import com.dawidpawliczek.matching.adapter.FileCommandLog
import com.dawidpawliczek.matching.adapter.KafkaMarketFeedSink
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.concurrent.CompletableFuture

fun main() {
    val bootstrapServers = System.getenv("KAFKA_BOOTSTRAP_SERVERS") ?: "localhost:9092"

    val producer =
        KafkaProducer<String, ByteArray>(
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
                put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
            },
        )

    val consumer =
        KafkaConsumer<String, ByteArray>(
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "matching")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            },
        )

    val commandLog = FileCommandLog(Path.of("journal.bin"))
    val orderService = OrderService(commandLog, KafkaMarketFeedSink(producer))

    val heartbeat = Path.of("/tmp/alive")
    val heartbeatIntervalMillis = 5_000L
    var lastHeartbeat = 0L

    val mainThread = Thread.currentThread()
    Runtime.getRuntime().addShutdownHook(
        Thread {
            consumer.wakeup()
            mainThread.join()
        },
    )

    try {
        consumer.subscribe(listOf(Topics.COMMANDS))
        while (true) {
            val records = consumer.poll(Duration.ofMillis(100))

            val now = System.currentTimeMillis()
            if (now - lastHeartbeat >= heartbeatIntervalMillis) {
                Files.write(heartbeat, ByteArray(0))
                lastHeartbeat = now
            }

            if (records.isEmpty) continue

            val futures = ArrayList<CompletableFuture<List<MarketEvent>>>(records.count())
            for (record in records) {
                futures.add(orderService.place(WireCodec.decodeCommand(record.value())))
            }

            CompletableFuture.allOf(*futures.toTypedArray()).join()
            producer.flush()
            consumer.commitSync()
        }
    } catch (_: WakeupException) {
    } finally {
        consumer.close()
        orderService.close()
        producer.close()
        commandLog.close()
    }
}
