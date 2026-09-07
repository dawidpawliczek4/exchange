package com.dawidpawliczek.app.marketData.adapter.outbound.jdbc

import com.dawidpawliczek.app.marketData.application.model.Candle
import com.dawidpawliczek.app.marketData.application.port.outbound.CandleRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JdbcCandleRepository(
    private val jdbc: JdbcClient,
) : CandleRepository {
    override fun findBetween(
        from: Instant,
        to: Instant,
    ): List<Candle> =
        jdbc
            .sql(SELECT)
            .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
            .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
            .query { rs, _ ->
                Candle(
                    bucketStart = rs.getLong("bucket_start"),
                    open = rs.getLong("open"),
                    high = rs.getLong("high"),
                    low = rs.getLong("low"),
                    close = rs.getLong("close"),
                    volume = rs.getLong("volume"),
                    quoteVolume = rs.getLong("quote_volume"),
                    tradeCount = rs.getLong("trade_count"),
                )
            }.list()

    private companion object {
        const val SELECT =
            """
            SELECT (extract(epoch FROM bucket) * 1000)::bigint AS bucket_start,
                   open, high, low, close, volume, quote_volume, trade_count
            FROM candles_5s
            WHERE bucket >= :from AND bucket < :to
            ORDER BY bucket
            """
    }
}
