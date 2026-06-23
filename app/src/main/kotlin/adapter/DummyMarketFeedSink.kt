package com.dawidpawliczek.app.adapter

import com.dawidpawliczek.engine.domain.Trade
import com.dawidpawliczek.engine.ports.MarketFeedSink

class DummyMarketFeedSink : MarketFeedSink {
    override fun publish(trades: List<Trade>) {
        println(trades)
    }
}
