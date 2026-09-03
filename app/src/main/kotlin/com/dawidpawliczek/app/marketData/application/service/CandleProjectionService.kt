package com.dawidpawliczek.app.marketData.application.service

import com.dawidpawliczek.app.marketData.application.port.outbound.MarketDataBroadcaster
import com.dawidpawliczek.contracts.event.MarketEvent
import com.dawidpawliczek.contracts.event.TradeEvent
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

class Candle(
    val intervalSeconds: Int = 5,
    val bucketStart: Long = System.currentTimeMillis(),
) {
    var open: Long? = null
    var high: Long? = null
    var low: Long? = null
    var close: Long? = null

    var volume: Long = 0
    var tradeCount: Long = 0

    var lastSeq: Long? = null
}

@Service
class CandleProjectionService(
    @Qualifier("candleBroadcaster") private val broadcaster: MarketDataBroadcaster,
    private val mapper: ObjectMapper,
) {
    private var candle = Candle()

    fun onEvent(event: MarketEvent) {
        if (event is TradeEvent) {
            val trade = event.trade

            if (event.timestamp > candle.bucketStart + candle.intervalSeconds * 1000) {
                broadcaster.broadcast(mapper.writeValueAsString(candle))
                candle = Candle()
            }

            candle.volume += trade.quantity * trade.price
            candle.tradeCount += 1

            candle.lastSeq = event.seq

            if (candle.open == null) candle.open = trade.price
            candle.high = maxOf(trade.price, candle.high ?: trade.price)
            candle.low = minOf(trade.price, candle.low ?: trade.price)
            candle.close = trade.price
        }
    }
}
