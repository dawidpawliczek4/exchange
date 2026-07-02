package com.dawidpawliczek.app.marketData.adapter.inbound.kafka

import com.dawidpawliczek.app.marketData.application.port.inbound.BroadcastMarketData
import com.dawidpawliczek.contracts.Topics
import com.dawidpawliczek.contracts.WireCodec
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class KafkaMarketFeedSubscriber(
    private val broadcastMarketData: BroadcastMarketData,
) {
    @KafkaListener(topics = [Topics.TRADES])
    fun onMessage(payload: ByteArray) {
        broadcastMarketData.broadcast(WireCodec.decodeEvent(payload))
    }
}
