package com.dawidpawliczek.app.order

import com.dawidpawliczek.app.adapter.DummyMarketFeedSink
import com.dawidpawliczek.app.adapter.FileCommandLog
import com.dawidpawliczek.engine.application.OrderService
import com.dawidpawliczek.engine.domain.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class OrderControllerRecoveryTest {
    @Test
    fun recoversStateAfterRestart(
        @TempDir tmp: Path,
    ) {
        val journal = tmp.resolve("journal.bin")

        val before = OrderController(OrderService(FileCommandLog(journal), DummyMarketFeedSink()))
        before.postOrder(OrderRequest(userId = 1, side = Side.BUY, price = 100, market = false, quantity = 5))
        val trades = before.postOrder(OrderRequest(userId = 2, side = Side.SELL, price = 100, market = false, quantity = 5)).get()
        assertEquals(1, trades.size)

        val after = OrderController(OrderService(FileCommandLog(journal), DummyMarketFeedSink()))

        assertEquals(before.getOrderBook(), after.getOrderBook())
        assertEquals(1, after.getOrderBook().size)
    }
}
