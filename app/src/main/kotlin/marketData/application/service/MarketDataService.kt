package com.dawidpawliczek.app.marketData.application.service

import com.dawidpawliczek.app.marketData.application.port.inbound.BroadcastMarketData
import com.dawidpawliczek.app.marketData.application.port.outbound.MarketDataBroadcaster
import com.dawidpawliczek.contracts.MarketEvent
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class MarketDataService(
    private val broadcaster: MarketDataBroadcaster,
    private val mapper: ObjectMapper,
) : BroadcastMarketData {
    override fun broadcast(event: MarketEvent) {
        broadcaster.broadcast(mapper.writeValueAsString(event))
    }
}
