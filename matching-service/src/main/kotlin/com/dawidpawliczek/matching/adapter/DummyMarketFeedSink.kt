package com.dawidpawliczek.matching.adapter

import com.dawidpawliczek.contracts.Trade
import com.dawidpawliczek.engine.ports.MarketFeedSink

class DummyMarketFeedSink : MarketFeedSink {
    override fun publish(trades: List<Trade>) {
        println(trades)
    }
}
