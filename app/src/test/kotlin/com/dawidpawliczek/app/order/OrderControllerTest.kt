package com.dawidpawliczek.app.order

import com.dawidpawliczek.app.TestcontainersConfiguration
import com.dawidpawliczek.app.auth.JwtService
import com.dawidpawliczek.app.auth.user.User
import com.dawidpawliczek.app.order.adapter.inbound.dto.OrderRequest
import com.dawidpawliczek.app.order.application.port.outbound.OrderCommandPublisher
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

    @Autowired
    lateinit var jwtService: JwtService

    @MockitoBean
    lateinit var publisher: OrderCommandPublisher

    @Test
    fun publishesCommandForAuthenticatedUserAndAccepts() {
        client
            .post()
            .uri("/order")
            .header("Authorization", "Bearer ${tokenFor(42L)}")
            .body(OrderRequest(Side.BUY, 100, false, 1))
            .exchange()
            .expectStatus()
            .isAccepted()

        verify(publisher).publish(PlaceOrderCommand(42, Side.BUY, 100, false, 1))
    }

    @Test
    fun rejectsInvalidRequest() {
        client
            .post()
            .uri("/order")
            .header("Authorization", "Bearer ${tokenFor(42L)}")
            .body(OrderRequest(Side.BUY, -1, false, 0))
            .exchange()
            .expectStatus()
            .isBadRequest()

        verifyNoInteractions(publisher)
    }

    @Test
    fun rejectsUnauthenticatedRequest() {
        client
            .post()
            .uri("/order")
            .body(OrderRequest(Side.BUY, 100, false, 1))
            .exchange()
            .expectStatus()
            .isForbidden()

        verifyNoInteractions(publisher)
    }

    private fun tokenFor(userId: Long) = jwtService.issueAccessToken(User(id = userId))
}
