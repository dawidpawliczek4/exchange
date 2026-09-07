package com.dawidpawliczek.app.marketData.application.port.outbound

import com.dawidpawliczek.app.marketData.application.model.Candle
import java.time.Instant

interface CandleRepository {
    fun findBetween(
        from: Instant,
        to: Instant,
    ): List<Candle>
}
