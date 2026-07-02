package com.dawidpawliczek.app.marketData.application.port.outbound

interface MarketDataBroadcaster {
    fun broadcast(json: String)
}
