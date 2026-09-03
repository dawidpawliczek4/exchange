package com.dawidpawliczek.app.marketData.adapter.inbound.kafka

import com.dawidpawliczek.app.marketData.application.service.CandleProjectionService
import com.dawidpawliczek.contracts.Topics
import com.dawidpawliczek.contracts.event.MarketEventCodec
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class KafkaCandleProjectionSubscriber(
    private val candleProjectionService: CandleProjectionService,
) {
    @KafkaListener(
        topics = [Topics.TRADES],
        groupId = "gateway-candles",
        properties = ["auto.offset.reset=earliest"],
    )
    fun onMessage(payload: ByteArray) {
        candleProjectionService.onEvent(MarketEventCodec.decode(payload))
    }
}
