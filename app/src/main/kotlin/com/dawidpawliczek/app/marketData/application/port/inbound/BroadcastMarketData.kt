package com.dawidpawliczek.app.marketData.application.port.inbound

import com.dawidpawliczek.contracts.event.MarketEvent

interface BroadcastMarketData {
    fun broadcast(event: MarketEvent)
}
