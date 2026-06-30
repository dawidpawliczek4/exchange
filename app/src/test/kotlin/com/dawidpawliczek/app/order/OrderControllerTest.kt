package com.dawidpawliczek.app.order

import com.dawidpawliczek.app.TestcontainersConfiguration
import com.dawidpawliczek.contracts.PlaceOrderCommand
import com.dawidpawliczek.contracts.Side
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.client.RestTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
class OrderControllerTest {
    @Autowired
    lateinit var client: RestTestClient

    @MockitoBean
    lateinit var publisher: OrderCommandPublisher

    @Test
    fun publishesCommandAndAccepts() {
        client
            .post()
            .uri("/order")
            .body(OrderRequest(0, Side.BUY, 100, false, 1))
            .exchange()
            .expectStatus()
            .isAccepted()

        verify(publisher).publish(PlaceOrderCommand(0, Side.BUY, 100, false, 1))
    }

    @Test
    fun rejectsInvalidRequest() {
        client
            .post()
            .uri("/order")
            .body(OrderRequest(0, Side.BUY, -1, false, 0))
            .exchange()
            .expectStatus()
            .isBadRequest()

        verifyNoInteractions(publisher)
    }
}
