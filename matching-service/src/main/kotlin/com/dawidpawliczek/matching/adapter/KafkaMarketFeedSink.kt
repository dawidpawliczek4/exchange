package com.dawidpawliczek.matching.adapter

import com.dawidpawliczek.contracts.Topics
import com.dawidpawliczek.contracts.Trade
import com.dawidpawliczek.contracts.WireCodec
import com.dawidpawliczek.engine.ports.MarketFeedSink
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord

class KafkaMarketFeedSink(
    private val kafkaProducer: KafkaProducer<String, ByteArray>,
) : MarketFeedSink {
    override fun publish(trades: List<Trade>) {
        for (trade in trades) {
            kafkaProducer.send(
                ProducerRecord(
                    Topics.TRADES,
                    WireCodec.encode(trade),
                ),
            )
        }
    }
}
