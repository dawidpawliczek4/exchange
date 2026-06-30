package com.dawidpawliczek.app.auth.session

import com.dawidpawliczek.app.TestcontainersConfiguration
import com.dawidpawliczek.app.auth.AuthTokens
import com.dawidpawliczek.app.auth.credentials.CredentialsRepository
import com.dawidpawliczek.app.auth.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import org.springframework.test.web.servlet.client.expectBody
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
class SessionControllerTest {
    @Autowired
    lateinit var client: RestTestClient

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var credentialsRepository: CredentialsRepository

    @Autowired
    lateinit var sessionRepository: SessionRepository

    @BeforeEach
    fun cleanup() {
        sessionRepository.deleteAll()
        credentialsRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `logout - removes session from database`() {
        val tokens = registerAndGetTokens()
        assertEquals(1, sessionRepository.count())

        post("/auth/session/logout", refreshBody(tokens.refreshToken))
            .expectStatus()
            .isOk()

        assertEquals(0, sessionRepository.count())
    }

    @Test
    fun `logout - refresh token cannot be reused after logout`() {
        val tokens = registerAndGetTokens()

        post("/auth/session/logout", refreshBody(tokens.refreshToken))
            .expectStatus()
            .isOk()

        post("/auth/session/refresh", refreshBody(tokens.refreshToken))
            .expectStatus()
            .isUnauthorized()
    }

    @Test
    fun `logout - unknown refresh token is a no-op`() {
        registerAndGetTokens()
        assertEquals(1, sessionRepository.count())

        post("/auth/session/logout", refreshBody("totally-bogus-token"))
            .expectStatus()
            .isOk()

        assertEquals(1, sessionRepository.count(), "Other sessions must not be affected")
    }

    @Test
    fun `logout - only the matching session is removed, other devices stay logged in`() {
        val deviceA = registerAndGetTokens()
        val deviceB = login()
        assertEquals(2, sessionRepository.count())

        post("/auth/session/logout", refreshBody(deviceA.refreshToken))
            .expectStatus()
            .isOk()

        assertEquals(1, sessionRepository.count())

        post("/auth/session/refresh", refreshBody(deviceB.refreshToken))
            .expectStatus()
            .isOk()
    }

    private fun registerAndGetTokens(): AuthTokens = authenticate("/auth/credentials/register")

    private fun login(): AuthTokens = authenticate("/auth/credentials/login")

    private fun authenticate(uri: String): AuthTokens {
        val tokens =
            post(uri, authRequest())
                .expectStatus()
                .isOk()
                .expectBody<AuthTokens>()
                .returnResult()
                .responseBody
        return assertNotNull(tokens)
    }

    private fun post(
        uri: String,
        body: Any,
    ) = client
        .post()
        .uri(uri)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()

    private fun authRequest(
        email: String = "user@test.com",
        password: String = "Test123!@",
    ) = mapOf("email" to email, "password" to password)

    private fun refreshBody(refreshToken: String) = mapOf("refreshToken" to refreshToken)
}
