package com.dawidpawliczek.e2e

import com.dawidpawliczek.app.ExchangeApplication
import com.dawidpawliczek.app.auth.AuthTokens
import com.dawidpawliczek.app.auth.user.UserRepository
import com.dawidpawliczek.contracts.CancelEvent
import com.dawidpawliczek.contracts.CancelStatus
import com.dawidpawliczek.contracts.TradeEvent
import com.dawidpawliczek.matching.MatchingRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.http.MediaType
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SpringBootTest(
    classes = [ExchangeApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@AutoConfigureRestTestClient
@Testcontainers
class TradeFlowE2eTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))

        @Container
        @JvmStatic
        val kafka = KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))

        @DynamicPropertySource
        @JvmStatic
        fun kafkaProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { kafka.bootstrapServers }
        }
    }

    @Autowired
    lateinit var client: RestTestClient

    @Autowired
    lateinit var listenerRegistry: KafkaListenerEndpointRegistry

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var userRepository: UserRepository

    @LocalServerPort
    var port: Int = 0

    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun resetDatabase() {
        userRepository.deleteAll()
    }

    @Test
    fun tradeFlowsFromOrderToWebSocket() {
        MatchingRunner(kafka.bootstrapServers, dir.resolve("journal.bin")).use { runner ->
            runner.start()

            val tokens = register()
            val userId = registeredUserId()
            val messages = LinkedBlockingQueue<String>()
            val session =
                StandardWebSocketClient()
                    .execute(collector(messages), "ws://localhost:$port/marketdata")
                    .get(10, TimeUnit.SECONDS)

            for (container in listenerRegistry.listenerContainers) {
                ContainerTestUtils.waitForAssignment(container, 1)
            }

            postOrder(tokens.accessToken, "SELL", 100, 5)
            postOrder(tokens.accessToken, "BUY", 100, 5)

            val json = messages.poll(30, TimeUnit.SECONDS)
            assertNotNull(json, "no trade arrived on /marketdata within 30s")

            val event = mapper.readValue(json, TradeEvent::class.java)
            assertEquals(100L, event.trade().price())
            assertEquals(5L, event.trade().quantity())
            assertEquals(userId, event.trade().makerUserId())
            assertEquals(userId, event.trade().takerUserId())
            assertTrue(event.seq() > 0)
            assertTrue(event.timestamp() > 0)

            session.close()
        }
    }

    @Test
    fun cancelsOrder() {
        MatchingRunner(kafka.bootstrapServers, dir.resolve("journal.bin")).use { runner ->
            runner.start()

            val tokens = register()
            val userId = registeredUserId()

            val messages = LinkedBlockingQueue<String>()
            val session =
                StandardWebSocketClient()
                    .execute(collector(messages), "ws://localhost:$port/marketdata")
                    .get(10, TimeUnit.SECONDS)

            for (container in listenerRegistry.listenerContainers) {
                ContainerTestUtils.waitForAssignment(container, 1)
            }

            postOrder(tokens.accessToken, "SELL", 100, 5)
            cancelOrder(tokens.accessToken, 0)

            val json = messages.poll(30, TimeUnit.SECONDS)
            assertNotNull(json, "no cancel event arrived on /marketdata within 30s")

            val event = mapper.readValue(json, CancelEvent::class.java)
            assertEquals(CancelStatus.CANCELED, event.status())
            assertEquals(0L, event.orderId())
            assertEquals(userId, event.userId())
            assertTrue(event.seq() > 0)
            assertTrue(event.timestamp() > 0)

            postOrder(tokens.accessToken, "BUY", 100, 5)
            assertNull(messages.poll(2, TimeUnit.SECONDS), "cancelled order must not trade")

            session.close()
        }
    }

    private fun register(): AuthTokens =
        client
            .post()
            .uri("/auth/credentials/register")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("email" to "trader@test.com", "password" to "Test123!@"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(AuthTokens::class.java)
            .returnResult()
            .responseBody!!

    private fun registeredUserId(): Long = userRepository.findAll().single().id

    private fun cancelOrder(
        token: String,
        id: Long,
    ) {
        client
            .delete()
            .uri("/order/$id")
            .header("Authorization", "Bearer $token")
            .exchange()
            .expectStatus()
            .isAccepted()
    }

    private fun postOrder(
        token: String,
        side: String,
        price: Long,
        quantity: Long,
    ) {
        client
            .post()
            .uri("/order")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("side" to side, "price" to price, "market" to false, "quantity" to quantity))
            .exchange()
            .expectStatus()
            .isAccepted()
    }

    private fun collector(messages: BlockingQueue<String>) =
        object : TextWebSocketHandler() {
            override fun handleTextMessage(
                session: WebSocketSession,
                message: TextMessage,
            ) {
                messages.add(message.payload)
            }
        }
}
