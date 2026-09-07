package com.dawidpawliczek.app.marketData.application.service

import com.dawidpawliczek.app.marketData.application.port.inbound.RecordTrades
import com.dawidpawliczek.app.marketData.application.port.outbound.TradeStore
import com.dawidpawliczek.contracts.event.MarketEvent
import com.dawidpawliczek.contracts.event.TradeEvent
import org.springframework.stereotype.Service

@Service
class TradeTapeService(
    private val tradeStore: TradeStore,
) : RecordTrades {
    override fun record(events: List<MarketEvent>) {
        val trades = events.filterIsInstance<TradeEvent>()
        if (trades.isNotEmpty()) tradeStore.append(trades)
    }
}
