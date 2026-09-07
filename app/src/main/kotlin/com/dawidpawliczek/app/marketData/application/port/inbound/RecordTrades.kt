package com.dawidpawliczek.app.marketData.application.port.inbound

import com.dawidpawliczek.contracts.event.MarketEvent

interface RecordTrades {
    fun record(events: List<MarketEvent>)
}
