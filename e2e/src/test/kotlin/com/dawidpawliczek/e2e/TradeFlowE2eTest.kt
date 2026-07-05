package com.dawidpawliczek.e2e

import com.dawidpawliczek.app.ExchangeApplication
import com.dawidpawliczek.app.auth.AuthTokens
import com.dawidpawliczek.matching.MatchingRunner
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
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

    @LocalServerPort
    var port: Int = 0

    @TempDir
    lateinit var dir: Path

    @Test
    fun tradeFlowsFromOrderToWebSocket() {
        MatchingRunner(kafka.bootstrapServers, dir.resolve("journal.bin")).use { runner ->
            runner.start()

            val tokens = register()
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

            val trade = JsonPath.parse(json)
            assertEquals(100, trade.read("$.trade.price"))
            assertEquals(5, trade.read("$.trade.quantity"))
            assertEquals(1, trade.read("$.trade.makerUserId"))
            assertEquals(1, trade.read("$.trade.takerUserId"))
            assertNotNull(trade.read<Any>("$.seq"))
            assertNotNull(trade.read<Any>("$.timestamp"))

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
