package com.dawidpawliczek.matching.adapter

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Properties

object KafkaFeedWatermark {
    fun lastPublishedSeq(
        bootstrapServers: String,
        topic: String,
    ): Long {
        val partition = TopicPartition(topic, 0)
        KafkaConsumer<String, ByteArray>(
            Properties().apply {
                put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
                put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java.name)
                put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
            },
        ).use { consumer ->
            consumer.assign(listOf(partition))
            val end = consumer.endOffsets(listOf(partition)).getValue(partition)
            val begin = consumer.beginningOffsets(listOf(partition)).getValue(partition)
            if (end == begin) return 0
            consumer.seek(partition, end - 1)
            while (true) {
                val records = consumer.poll(Duration.ofSeconds(1)).records(partition)
                if (records.isNotEmpty()) return ByteBuffer.wrap(records.last().value()).getLong()
            }
        }
    }
}
