package com.dawidpawliczek.app.marketData.application.port.outbound

import com.dawidpawliczek.contracts.event.TradeEvent

interface TradeStore {
    fun append(trades: List<TradeEvent>)
}
