package com.dawidpawliczek.app.marketData.application.service

import com.dawidpawliczek.app.marketData.application.model.Candle
import com.dawidpawliczek.app.marketData.application.port.inbound.GetCandles
import com.dawidpawliczek.app.marketData.application.port.outbound.CandleRepository
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class CandleQueryService(
    private val candleRepository: CandleRepository,
) : GetCandles {
    override fun candles(
        fromMillis: Long?,
        toMillis: Long?,
    ): List<Candle> {
        val to = toMillis?.let(Instant::ofEpochMilli) ?: Instant.now()
        val from = fromMillis?.let(Instant::ofEpochMilli) ?: to.minus(DEFAULT_SPAN)
        require(from < to) { "from must be before to" }
        require(Duration.between(from, to) <= MAX_SPAN) { "range must not exceed ${MAX_SPAN.toHours()} hours" }
        return candleRepository.findBetween(from, to)
    }

    private companion object {
        val DEFAULT_SPAN: Duration = Duration.ofHours(1)
        val MAX_SPAN: Duration = Duration.ofHours(24)
    }
}
