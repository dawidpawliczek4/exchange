package com.dawidpawliczek.app.adapter.marketData

import com.dawidpawliczek.contracts.Topics
import com.dawidpawliczek.contracts.WireCodec
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Bridges the market-data feed to WebSocket subscribers. The matching service publishes executed
 * trades to `orders.trades`; we decode each one off the wire and fan it out as JSON. Replaces the
 * old in-process MarketFeedSink now that the gateway no longer embeds the engine.
 */
@Component
class MarketDataConsumer(
    private val handler: MarketDataHandler,
    private val mapper: ObjectMapper,
) {
    @KafkaListener(topics = [Topics.TRADES])
    fun onTrade(payload: ByteArray) {
        val trade = WireCodec.decodeTrade(payload)
        handler.broadcast(mapper.writeValueAsString(trade))
    }
}
