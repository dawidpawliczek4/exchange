package com.dawidpawliczek.app.adapter.marketData

import com.dawidpawliczek.engine.domain.Trade
import com.dawidpawliczek.engine.ports.MarketFeedSink
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Component
class WebSocketMarketFeedSink(
    private val handler: MarketDataHandler,
    private val mapper: ObjectMapper,
) : MarketFeedSink {
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1 shl 14),
            { Thread(it, "marketdata-broadcast").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardPolicy(),
        )

    override fun publish(trades: List<Trade>) {
        if (trades.isEmpty()) return
        executor.execute { handler.broadcast(mapper.writeValueAsString(trades)) }
    }
}
