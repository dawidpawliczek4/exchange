package com.dawidpawliczek.app.order

import com.dawidpawliczek.engine.domain.Side
import com.dawidpawliczek.engine.domain.Trade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.RepeatedTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.test.web.servlet.client.expectBody
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors


@SpringBootTest
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class OrderControllerParallelTest {

    @Autowired
    lateinit var client: RestTestClient;

    @RepeatedTest(5)
    fun concurrentOrdersConserveQuantity() {

        val perSide = 500

        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val startGate = CountDownLatch(1)
        val trades = CopyOnWriteArrayList<Trade>()

        val sides = (List(perSide) { Side.BUY } + List(perSide) { Side.SELL}).shuffled()

        val futures = sides.map { side ->
            pool.submit {
                startGate.await()
                val res: List<Trade> = postOrder(OrderRequest(1, side, 100, false, 1))
                trades.addAll(res)
            }
        }

        startGate.countDown()
        futures.forEach { it.get() }
        pool.shutdown()

        val matchedVolume = trades.sumOf { it.quantity }
        assertEquals(perSide.toLong(), matchedVolume)
        trades.forEach { assertEquals(100, it.price) }
    }

    private inline fun <reified T: Any> postOrder(body: OrderRequest)=
        client.post()
            .uri("/order")
            .body(body)
            .exchange()
            .expectBody<T>()
            .returnResult()
            .responseBody!!

}