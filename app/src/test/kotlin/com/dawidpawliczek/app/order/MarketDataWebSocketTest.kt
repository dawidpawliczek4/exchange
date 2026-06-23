package com.dawidpawliczek.app.order

import com.dawidpawliczek.app.adapter.marketData.MarketDataHandler
import com.dawidpawliczek.engine.domain.Side
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class MarketDataWebSocketTest {
    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var controller: OrderController

    @Autowired
    lateinit var handler: MarketDataHandler

    @Test
    fun broadcastsTradeOnMatch() {
        val received = CountDownLatch(1)
        val payload = AtomicReference<String>()

        val session =
            StandardWebSocketClient()
                .execute(
                    object : TextWebSocketHandler() {
                        override fun handleTextMessage(
                            s: WebSocketSession,
                            m: TextMessage,
                        ) {
                            payload.set(m.payload)
                            received.countDown()
                        }
                    },
                    "ws://localhost:$port/marketdata",
                ).get(2, TimeUnit.SECONDS)

        await().atMost(2, TimeUnit.SECONDS).until { handler.subscriberCount() >= 1 }

        controller.postOrder(OrderRequest(0, Side.BUY, 100, false, 1)).get(2, TimeUnit.SECONDS)
        controller.postOrder(OrderRequest(1, Side.SELL, 100, false, 1)).get(2, TimeUnit.SECONDS) // matches -> trade

        assertTrue(received.await(3, TimeUnit.SECONDS), "expected a market-data broadcast")
        assertTrue(payload.get().contains("\"price\":100"), "broadcast payload: ${payload.get()}")
        session.close()
    }
}
