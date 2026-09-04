package com.dawidpawliczek.app.error

import com.dawidpawliczek.app.TestcontainersConfiguration
import com.dawidpawliczek.app.auth.AuthTokens
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
class ProblemDetailShapeTest {
    @Autowired
    lateinit var client: RestTestClient

    private lateinit var bearer: String

    @BeforeEach
    fun registerUser() {
        val tokens =
            client
                .post()
                .uri("/auth/credentials/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("email" to "shape-${System.nanoTime()}@test.com", "password" to "Test123!@"))
                .exchange()
                .expectBody(AuthTokens::class.java)
                .returnResult()
                .responseBody
        bearer = "Bearer ${assertNotNull(tokens).accessToken}"
    }

    @Test
    fun `unexpected exception is 500 with no detail`() {
        val body = problem(client.get().uri("/test/boom").header("Authorization", bearer), HttpStatus.INTERNAL_SERVER_ERROR)

        assertEquals("Internal Server Error", body["title"])
        assertNull(body["detail"])
    }

    @Test
    fun `unexpected exception does not leak its message`() {
        val raw =
            client
                .get()
                .uri("/test/boom")
                .header("Authorization", bearer)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody

        assertFalse(assertNotNull(raw).contains("shard 7"), "Raw body leaked the exception message: $raw")
    }

    @Test
    fun `missing token is 401`() {
        val body = problem(client.get().uri("/test/protected"), HttpStatus.UNAUTHORIZED)

        assertEquals("Authentication required", body["detail"])
    }

    @Test
    fun `invalid token is 401`() {
        val body = problem(client.get().uri("/test/protected").header("Authorization", "Bearer garbage"), HttpStatus.UNAUTHORIZED)

        assertEquals("Authentication required", body["detail"])
    }

    @Test
    fun `unknown path is 404`() {
        val body = problem(client.get().uri("/no-such-endpoint").header("Authorization", bearer), HttpStatus.NOT_FOUND)

        assertEquals("Not Found", body["title"])
    }

    @Test
    fun `unknown path without a token is 401, not a leak of routing`() {
        problem(client.get().uri("/no-such-endpoint"), HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `malformed json is 400`() {
        val body =
            problem(
                client
                    .post()
                    .uri("/auth/credentials/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{ not json"),
                HttpStatus.BAD_REQUEST,
            )

        assertEquals("Bad Request", body["title"])
    }

    @Test
    fun `unsupported method is 405`() {
        val body = problem(client.get().uri("/auth/credentials/login"), HttpStatus.METHOD_NOT_ALLOWED)

        assertEquals("Method Not Allowed", body["title"])
    }

    @Test
    fun `domain exception carries its message as detail`() {
        val body =
            problem(
                client
                    .post()
                    .uri("/auth/credentials/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapOf("email" to "nobody@test.com", "password" to "Test123!@")),
                HttpStatus.UNAUTHORIZED,
            )

        assertEquals("Invalid credentials", body["detail"])
    }

    @Test
    fun `validation failure lists field errors`() {
        val body =
            problem(
                client
                    .post()
                    .uri("/auth/credentials/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapOf("email" to "not-an-email", "password" to "Test123!@")),
                HttpStatus.BAD_REQUEST,
            )

        assertEquals(listOf(mapOf("field" to "email", "message" to "Email is invalid")), body["errors"])
    }

    private fun problem(
        request: RequestHeadersSpec<*>,
        expected: HttpStatus,
    ): Map<String, Any> {
        val body =
            request
                .exchange()
                .expectStatus()
                .isEqualTo(expected)
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(object : ParameterizedTypeReference<Map<String, Any>>() {})
                .returnResult()
                .responseBody
        assertEquals(expected.value(), assertNotNull(body)["status"])
        return body
    }
}
