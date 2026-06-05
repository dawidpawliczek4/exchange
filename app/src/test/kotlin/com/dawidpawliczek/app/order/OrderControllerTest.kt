package com.dawidpawliczek.app.order

import com.dawidpawliczek.engine.Side
import com.dawidpawliczek.engine.Trade
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.test.web.servlet.client.expectBody

@SpringBootTest
@AutoConfigureRestTestClient
class OrderControllerTest {

    @Autowired
    lateinit var client: RestTestClient;

    @Test
    fun sell1AndBuy1() {
        val res: List<Trade> = postOrder(OrderRequest(0, Side.BUY, 100, false, 1))
        Assertions.assertEquals(emptyList<Trade>(), res)

        val res2: List<Trade> = postOrder(OrderRequest(1, Side.SELL, 100, false, 1))
        Assertions.assertEquals(listOf(Trade(0, 0, 1, 1, 100, 1)), res2)
    }

    private inline fun <reified T: Any> postOrder(body: OrderRequest)=
        client.post()
            .uri("/order")
            .body(body)
            .exchange()
            .expectStatus().isOk()
            .expectBody<T>()
            .returnResult()
            .responseBody!!

}