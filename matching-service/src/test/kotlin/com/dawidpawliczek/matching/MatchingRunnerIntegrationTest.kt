package com.dawidpawliczek.matching

import com.dawidpawliczek.contracts.PlaceOrderCommand
import com.dawidpawliczek.contracts.Side
import com.dawidpawliczek.contracts.Topics
import com.dawidpawliczek.contracts.Trade
import com.dawidpawliczek.contracts.TradeEvent
import com.dawidpawliczek.contracts.WireCodec
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.UUID

@Testcontainers
class MatchingRunnerIntegrationTest {
    @Container
    val kafka = KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))

    @TempDir
    lateinit var dir: Path

    private val commandsPartition = TopicPartition(Topics.COMMANDS, 0)

    private fun admin(): Admin = Admin.create(Properties().apply { put("bootstrap.servers", kafka.bootstrapServers) })

    private fun createTopics() {
        admin().use {
            it
                .createTopics(
                    listOf(
                        NewTopic(Topics.COMMANDS, 1, 1),
                        NewTopic(Topics.TRADES, 1, 1),
                    ),
                ).all()
                .get()
        }
    }

    private fun produceCommands(vararg commands: PlaceOrderCommand) {
        KafkaProducer<String, ByteArray>(
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
            },
        ).use { producer ->
            for (command in commands) {
                producer.send(ProducerRecord(Topics.COMMANDS, WireCodec.encode(command))).get()
            }
        }
    }

    private fun committedOffset(): Long? =
        admin().use {
            it
                .listConsumerGroupOffsets("matching")
                .partitionsToOffsetAndMetadata()
                .get()[commandsPartition]
                ?.offset()
        }

    private fun readAllTrades(): List<Trade> {
        KafkaConsumer<String, ByteArray>(
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "trades-reader-${UUID.randomUUID()}")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            },
        ).use { consumer ->
            consumer.assign(listOf(TopicPartition(Topics.TRADES, 0)))
            consumer.seekToBeginning(consumer.assignment())
            val trades = mutableListOf<Trade>()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                for (record in consumer.poll(Duration.ofMillis(250))) {
                    trades.add((WireCodec.decodeEvent(record.value()) as TradeEvent).trade())
                }
            }
            return trades
        }
    }

    private fun awaitUntil(
        description: String,
        timeoutMillis: Long = 60_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for: $description")
    }

    private fun sell(
        price: Long,
        quantity: Long,
    ) = PlaceOrderCommand(1, Side.SELL, price, false, quantity)

    private fun buy(
        price: Long,
        quantity: Long,
    ) = PlaceOrderCommand(2, Side.BUY, price, false, quantity)

    @Test
    fun noDuplicatesAfterLostCommit() {
        createTopics()
        val journal = dir.resolve("journal.bin")
        produceCommands(sell(100, 5), sell(101, 5), buy(100, 5))

        MatchingRunner(kafka.bootstrapServers, journal).use { runner ->
            runner.start()
            awaitUntil("runner1 processed offsets 0..2") { runner.lastSourceOffset() == 2L }
        }

        admin().use {
            it
                .alterConsumerGroupOffsets("matching", mapOf(commandsPartition to OffsetAndMetadata(0)))
                .all()
                .get()
        }

        MatchingRunner(kafka.bootstrapServers, journal).use { runner ->
            runner.start()
            produceCommands(buy(101, 5))
            awaitUntil("runner2 committed offset 4") { committedOffset() == 4L }
            assertEquals(3L, runner.lastSourceOffset())
        }

        val trades = readAllTrades()
        assertEquals(
            listOf(
                Trade(0, 1, 2, 2, 100, 5),
                Trade(1, 1, 3, 2, 101, 5),
            ),
            trades,
        )
    }

    @Test
    fun startsFromEarliestWhenWalEmpty() {
        createTopics()
        produceCommands(sell(100, 5), buy(100, 5))

        MatchingRunner(kafka.bootstrapServers, dir.resolve("journal.bin")).use { runner ->
            runner.start()
            awaitUntil("runner committed offset 2") { committedOffset() == 2L }
            assertEquals(1L, runner.lastSourceOffset())
        }

        assertEquals(listOf(Trade(0, 1, 1, 2, 100, 5)), readAllTrades())
    }
}
