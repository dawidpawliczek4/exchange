package com.dawidpawliczek.agent

import com.dawidpawliczek.contracts.Asset
import com.dawidpawliczek.contracts.Side
import com.dawidpawliczek.contracts.Topics
import com.dawidpawliczek.contracts.command.CommandCodec
import com.dawidpawliczek.contracts.command.DepositCommand
import com.dawidpawliczek.contracts.command.OrderCommand
import com.dawidpawliczek.contracts.command.PlaceOrderCommand
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties
import java.util.SplittableRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

private const val FIRST_BOT_USER_ID = 1_000_000L
private const val CASH_PER_BOT = 1_000_000_000L
private const val ASSET_PER_BOT = 1_000_000L

fun main() {
    val bootstrapServers = System.getenv("KAFKA_BOOTSTRAP_SERVERS") ?: "localhost:9092"
    val botCount = System.getenv("BOT_COUNT")?.toInt() ?: 100
    val midPrice = System.getenv("MID_PRICE")?.toLong() ?: 10_000L
    val priceBand = System.getenv("PRICE_BAND")?.toLong() ?: 100L
    val seed = System.getenv("SEED")?.toLong() ?: 42L

    val producer =
        KafkaProducer<String, ByteArray>(
            Properties().apply {
                put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
                put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java.name)
                put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
            },
        )
    Runtime.getRuntime().addShutdownHook(Thread { producer.close() })

    val master = SplittableRandom(seed)
    val done = CountDownLatch(botCount)
    Executors.newVirtualThreadPerTaskExecutor().use { executor ->
        repeat(botCount) { i ->
            val bot = ZeroIntelligenceBot(FIRST_BOT_USER_ID + i, midPrice, priceBand, master.split())
            executor.submit {
                try {
                    bot.run(producer)
                } finally {
                    done.countDown()
                }
            }
        }
        done.await()
    }
}

private class ZeroIntelligenceBot(
    private val userId: Long,
    private val midPrice: Long,
    private val priceBand: Long,
    private val random: SplittableRandom,
) {
    fun run(producer: KafkaProducer<String, ByteArray>) {
        send(producer, DepositCommand(userId, Asset.QUOTE, CASH_PER_BOT))
        send(producer, DepositCommand(userId, Asset.BASE, ASSET_PER_BOT))
        while (!Thread.currentThread().isInterrupted) {
            send(producer, nextOrder())
            Thread.sleep(random.nextLong(50, 500))
        }
    }

    private fun nextOrder(): PlaceOrderCommand {
        val side = if (random.nextBoolean()) Side.BUY else Side.SELL
        val price = random.nextLong(midPrice - priceBand, midPrice + priceBand + 1)
        val quantity = random.nextLong(1, 11)
        return PlaceOrderCommand(userId, side, price, false, quantity)
    }

    private fun send(
        producer: KafkaProducer<String, ByteArray>,
        command: OrderCommand,
    ) {
        producer.send(ProducerRecord(Topics.COMMANDS, CommandCodec.encode(command)))
    }
}
