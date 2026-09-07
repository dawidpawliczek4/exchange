package com.dawidpawliczek.app.marketData.adapter.inbound.kafka

import com.dawidpawliczek.app.marketData.application.port.inbound.RecordTrades
import com.dawidpawliczek.contracts.Topics
import com.dawidpawliczek.contracts.event.MarketEventCodec
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class KafkaTradeTapeSubscriber(
    private val recordTrades: RecordTrades,
) {
    @KafkaListener(
        topics = [Topics.TRADES],
        groupId = "gateway-trades",
        batch = "true",
        properties = ["auto.offset.reset=earliest"],
    )
    fun onMessages(payloads: List<ByteArray>) {
        recordTrades.record(payloads.map { MarketEventCodec.decode(it) })
    }
}
