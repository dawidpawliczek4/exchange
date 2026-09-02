package com.dawidpawliczek.matching

import com.dawidpawliczek.contracts.Asset
import com.dawidpawliczek.contracts.Side
import com.dawidpawliczek.contracts.Topics
import com.dawidpawliczek.contracts.Trade
import com.dawidpawliczek.contracts.command.CommandCodec
import com.dawidpawliczek.contracts.command.DepositCommand
import com.dawidpawliczek.contracts.command.OrderCommand
import com.dawidpawliczek.contracts.command.PlaceOrderCommand
import com.dawidpawliczek.contracts.event.AccountEvent
import com.dawidpawliczek.contracts.event.AccountEventCodec
import com.dawidpawliczek.contracts.event.DepositAccepted
import com.dawidpawliczek.contracts.event.MarketEvent
import com.dawidpawliczek.contracts.event.MarketEventCodec
import com.dawidpawliczek.contracts.event.OrderRejected
import com.dawidpawliczek.contracts.event.TradeEvent
import com.dawidpawliczek.engine.application.OrderService
import com.dawidpawliczek.matching.adapter.FileCommandLog
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
                        NewTopic(Topics.ACCOUNT, 1, 1),
                    ),
                ).all()
                .get()
        }
    }

    private fun produceCommands(vararg commands: OrderCommand) {
        KafkaProducer<String, ByteArray>(
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
            },
        ).use { producer ->
            for (command in commands) {
                producer.send(ProducerRecord(Topics.COMMANDS, CommandCodec.encode(command))).get()
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

    private fun readAllTrades(): List<Trade> = readAllMarketEvents().map { (it as TradeEvent).trade() }

    private fun readAllMarketEvents(): List<MarketEvent> {
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
            val events = mutableListOf<MarketEvent>()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                for (record in consumer.poll(Duration.ofMillis(250))) {
                    events.add(MarketEventCodec.decode(record.value()))
                }
            }
            return events
        }
    }

    private fun readAllAccountEvents(): List<Pair<String, AccountEvent>> {
        KafkaConsumer<String, ByteArray>(
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers)
                put(ConsumerConfig.GROUP_ID_CONFIG, "account-reader-${UUID.randomUUID()}")
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            },
        ).use { consumer ->
            consumer.assign(listOf(TopicPartition(Topics.ACCOUNT, 0)))
            consumer.seekToBeginning(consumer.assignment())
            val events = mutableListOf<Pair<String, AccountEvent>>()
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                for (record in consumer.poll(Duration.ofMillis(250))) {
                    events.add(record.key() to AccountEventCodec.decode(record.value()))
                }
            }
            return events
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

    private fun seeds() =
        arrayOf<OrderCommand>(
            DepositCommand(1, Asset.BASE, 1_000_000),
            DepositCommand(2, Asset.QUOTE, 1_000_000),
        )

    @Test
    fun noDuplicatesAfterLostCommit() {
        createTopics()
        val journal = dir.resolve("journal.bin")
        produceCommands(*seeds(), sell(100, 5), sell(101, 5), buy(100, 5))

        MatchingRunner(kafka.bootstrapServers, journal).use { runner ->
            runner.start()
            awaitUntil("runner1 processed offsets 0..4") { runner.lastSourceOffset() == 4L }
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
            awaitUntil("runner2 committed offset 6") { committedOffset() == 6L }
            assertEquals(5L, runner.lastSourceOffset())
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
        produceCommands(*seeds(), sell(100, 5), buy(100, 5))

        MatchingRunner(kafka.bootstrapServers, dir.resolve("journal.bin")).use { runner ->
            runner.start()
            awaitUntil("runner committed offset 4") { committedOffset() == 4L }
            assertEquals(3L, runner.lastSourceOffset())
        }

        assertEquals(listOf(Trade(0, 1, 1, 2, 100, 5)), readAllTrades())
    }

    @Test
    fun depositReachesAccountTopicKeyedByUser() {
        createTopics()
        produceCommands(DepositCommand(42, Asset.QUOTE, 1000))

        MatchingRunner(kafka.bootstrapServers, dir.resolve("journal.bin")).use { runner ->
            runner.start()
            awaitUntil("runner committed offset 1") { committedOffset() == 1L }
        }

        val records = readAllAccountEvents()
        assertEquals(1, records.size)
        val (key, event) = records.first()
        assertEquals("42", key)
        val accepted = event as DepositAccepted
        assertEquals(42L, accepted.userId())
        assertEquals(Asset.QUOTE, accepted.asset())
        assertEquals(1L, accepted.seq())
    }

    @Test
    fun nsfOrderEmitsOrderRejectedOnAccountTopic() {
        createTopics()
        produceCommands(buy(100, 5))

        MatchingRunner(kafka.bootstrapServers, dir.resolve("journal.bin")).use { runner ->
            runner.start()
            awaitUntil("runner committed offset 1") { committedOffset() == 1L }
        }

        assertEquals(emptyList<Trade>(), readAllTrades())
        val records = readAllAccountEvents()
        assertEquals(1, records.size)
        val (key, event) = records.first()
        assertEquals("2", key)
        val rejected = event as OrderRejected
        assertEquals(2L, rejected.userId())
    }

    @Test
    fun recoveryRepublishesEventsLostBetweenWalSyncAndProducerFlush() {
        createTopics()
        val journal = dir.resolve("journal.bin")
        produceCommands(*seeds(), sell(100, 5), buy(100, 5))

        MatchingRunner(kafka.bootstrapServers, journal).use { runner ->
            runner.start()
            awaitUntil("runner1 committed offset 4") { committedOffset() == 4L }
        }

        produceCommands(sell(101, 5), buy(101, 5), PlaceOrderCommand(3, Side.BUY, 100, false, 1))
        val neverFlushedMarket = mutableListOf<MarketEvent>()
        val neverFlushedAccount = mutableListOf<AccountEvent>()
        val log = FileCommandLog(journal)
        val crashed =
            OrderService(
                log,
                { neverFlushedMarket.addAll(it) },
                { neverFlushedAccount.addAll(it) },
                System::currentTimeMillis,
                1,
                2,
            )
        crashed.submit(sell(101, 5), 4).join()
        crashed.submit(buy(101, 5), 5).join()
        crashed.submit(PlaceOrderCommand(3, Side.BUY, 100, false, 1), 6).join()
        crashed.close()
        log.close()
        assertEquals(1, neverFlushedMarket.size)
        assertEquals(1, neverFlushedAccount.size)
        assertEquals(listOf(Trade(0, 1, 1, 2, 100, 5)), readAllTrades())

        MatchingRunner(kafka.bootstrapServers, journal).use { runner ->
            runner.start()
            assertEquals(6L, runner.lastSourceOffset())
            produceCommands(sell(105, 1))
            awaitUntil("runner2 committed offset 8") { committedOffset() == 8L }
            assertEquals(7L, runner.lastSourceOffset())
        }

        val marketEvents = readAllMarketEvents()
        assertEquals(2, marketEvents.size)
        val first = marketEvents.first() as TradeEvent
        assertEquals(1L, first.seq())
        assertEquals(Trade(0, 1, 1, 2, 100, 5), first.trade())
        assertEquals(neverFlushedMarket, marketEvents.drop(1))

        val accountEvents = readAllAccountEvents()
        assertEquals(listOf("1", "2", "3"), accountEvents.map { it.first })
        assertEquals(listOf(1L, 2L, 3L), accountEvents.map { it.second.seq() })
        assertEquals(neverFlushedAccount, accountEvents.drop(2).map { it.second })
    }
}
