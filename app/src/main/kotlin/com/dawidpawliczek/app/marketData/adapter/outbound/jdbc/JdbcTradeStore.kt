package com.dawidpawliczek.app.marketData.adapter.outbound.jdbc

import com.dawidpawliczek.app.marketData.application.port.outbound.TradeStore
import com.dawidpawliczek.contracts.event.TradeEvent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JdbcTradeStore(
    private val jdbc: JdbcTemplate,
) : TradeStore {
    override fun append(trades: List<TradeEvent>) {
        jdbc.batchUpdate(INSERT, trades, trades.size) { ps, event ->
            val trade = event.trade
            ps.setObject(1, OffsetDateTime.ofInstant(Instant.ofEpochMilli(event.timestamp), ZoneOffset.UTC))
            ps.setLong(2, event.seq)
            ps.setLong(3, trade.makerId)
            ps.setLong(4, trade.makerUserId)
            ps.setLong(5, trade.takerId)
            ps.setLong(6, trade.takerUserId)
            ps.setLong(7, trade.price)
            ps.setLong(8, trade.quantity)
        }
    }

    private companion object {
        const val INSERT =
            """
            INSERT INTO trades (ts, seq, maker_order_id, maker_user_id, taker_order_id, taker_user_id, price, quantity)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """
    }
}
