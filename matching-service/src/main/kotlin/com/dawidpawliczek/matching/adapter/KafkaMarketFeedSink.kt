package com.dawidpawliczek.matching.adapter

import com.dawidpawliczek.contracts.MarketEvent
import com.dawidpawliczek.contracts.Topics
import com.dawidpawliczek.contracts.WireCodec
import com.dawidpawliczek.engine.ports.MarketFeedSink
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord

class KafkaMarketFeedSink(
    private val kafkaProducer: KafkaProducer<String, ByteArray>,
) : MarketFeedSink {
    override fun publish(events: List<MarketEvent>) {
        for (event in events) {
            kafkaProducer.send(
                ProducerRecord(
                    Topics.TRADES,
                    PARTITION_KEY,
                    WireCodec.encode(event),
                ),
            )
        }
    }

    private companion object {
        const val PARTITION_KEY = "TRADE"
    }
}
