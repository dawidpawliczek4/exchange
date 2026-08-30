package com.dawidpawliczek.matching.adapter

import com.dawidpawliczek.contracts.Topics
import com.dawidpawliczek.contracts.event.AccountEvent
import com.dawidpawliczek.contracts.event.AccountEventCodec
import com.dawidpawliczek.engine.ports.AccountFeedSink
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord

class KafkaAccountFeedSink(
    private val kafkaProducer: KafkaProducer<String, ByteArray>,
) : AccountFeedSink {
    override fun publish(events: List<AccountEvent>) {
        for (event in events) {
            kafkaProducer.send(
                ProducerRecord(
                    Topics.ACCOUNT,
                    event.userId().toString(),
                    AccountEventCodec.encode(event),
                ),
            )
        }
    }
}
