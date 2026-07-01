package com.dawidpawliczek.matching.adapter

import com.dawidpawliczek.contracts.MarketEvent
import com.dawidpawliczek.engine.ports.MarketFeedSink

class DummyMarketFeedSink : MarketFeedSink {
    override fun publish(events: List<MarketEvent>) {
        println(events)
    }
}
