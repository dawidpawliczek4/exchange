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
import org.apache.kafka.common.TopicPartition
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

class MatchingRunner(
    private val bootstrapServers: String,
    private val journalPath: Path,
    private val heartbeatPath: Path? = null,
    private val groupId: String = "matching",
) : AutoCloseable {
    @Volatile
    private var stopped = false
    private var loopThread: Thread? = null
    private val commandsPartition = TopicPartition(Topics.COMMANDS, 0)
    private lateinit var producer: KafkaProducer<String, ByteArray>
    private lateinit var consumer: KafkaConsumer<String, ByteArray>
    private lateinit var commandLog: FileCommandLog
    private lateinit var orderService: OrderService

    fun start() {
        if (loopThread != null) return
        producer =
            KafkaProducer(
                Properties().apply {
                    put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                    put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                    put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
                    put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                },
            )
        consumer =
            KafkaConsumer(
                Properties().apply {
                    put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                    put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
                    put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                    put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
                    put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                    put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                },
            )
        commandLog = FileCommandLog(journalPath)
        orderService = OrderService(commandLog, KafkaMarketFeedSink(producer))
        loopThread = Thread(::runLoop, "matching-runner").also { it.start() }
    }

    fun awaitTermination() = loopThread?.join()

    fun lastSourceOffset(): Long = orderService.lastSourceOffset()

    override fun close() {
        val thread = loopThread ?: return
        stopped = true
        consumer.wakeup()
        thread.join()
    }

    private fun runLoop() {
        try {
            consumer.assign(listOf(commandsPartition))
            val wal = orderService.lastSourceOffset()
            if (wal >= 0) consumer.seek(commandsPartition, wal + 1)
            val heartbeatIntervalMillis = 5_000L
            var lastHeartbeat = 0L
            while (!stopped) {
                val records = consumer.poll(Duration.ofMillis(100))

                if (heartbeatPath != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeat >= heartbeatIntervalMillis) {
                        Files.write(heartbeatPath, ByteArray(0))
                        lastHeartbeat = now
                    }
                }

                if (records.isEmpty) continue

                val futures = ArrayList<CompletableFuture<List<MarketEvent>>>(records.count())
                for (record in records) {
                    futures.add(orderService.place(WireCodec.decodeCommand(record.value()), record.offset()))
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
}
