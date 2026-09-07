package com.dawidpawliczek.app.marketData.application.port.inbound

import com.dawidpawliczek.app.marketData.application.model.Candle

interface GetCandles {
    fun candles(
        fromMillis: Long?,
        toMillis: Long?,
    ): List<Candle>
}
