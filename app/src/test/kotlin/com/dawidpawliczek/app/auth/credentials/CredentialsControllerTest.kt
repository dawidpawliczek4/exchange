package com.dawidpawliczek.app.auth.credentials

import com.dawidpawliczek.app.TestcontainersConfiguration
import com.dawidpawliczek.app.auth.AuthTokens
import com.dawidpawliczek.app.auth.session.SessionRepository
import com.dawidpawliczek.app.auth.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
class CredentialsControllerTest {
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
    fun `register - creates user, credentials, and returns tokens`() {
        val tokens = register()

        assertTrue(tokens.accessToken.isNotEmpty())
        assertTrue(tokens.refreshToken.isNotEmpty())
        assertEquals(1, userRepository.count())
        assertEquals(1, credentialsRepository.count())
        assertEquals(1, sessionRepository.count())

        val credentials = credentialsRepository.findByEmail("user@test.com")
        assertNotNull(credentials)
        assertEquals("user@test.com", credentials.email)
    }

    @Test
    fun `register - duplicate email fails`() {
        register()

        client
            .post()
            .uri("/auth/credentials/register")
            .contentType(MediaType.APPLICATION_JSON)
            .body(authRequest())
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `register - weak password fails`() {
        client
            .post()
            .uri("/auth/credentials/register")
            .contentType(MediaType.APPLICATION_JSON)
            .body(authRequest(password = "weak"))
            .exchange()
            .expectStatus()
            .isBadRequest()

        assertEquals(0, userRepository.count(), "No user should be created for invalid password")
    }

    @Test
    fun `login - logs user, returns tokens`() {
        register()

        val tokens =
            client
                .post()
                .uri("/auth/credentials/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(authRequest())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(AuthTokens::class.java)
                .returnResult()
                .responseBody

        assertNotNull(tokens)
        assertTrue(tokens.accessToken.isNotEmpty())
        assertTrue(tokens.refreshToken.isNotEmpty())
    }

    @Test
    fun `login - wrong password fails`() {
        register()

        client
            .post()
            .uri("/auth/credentials/login")
            .contentType(MediaType.APPLICATION_JSON)
            .body(authRequest(password = "WrongPass1!"))
            .exchange()
            .expectStatus()
            .isUnauthorized()
    }

    @Test
    fun `login - wrong email fails`() {
        client
            .post()
            .uri("/auth/credentials/login")
            .contentType(MediaType.APPLICATION_JSON)
            .body(authRequest())
            .exchange()
            .expectStatus()
            .isUnauthorized()
    }

    @Test
    fun `valid access token authenticates the request`() {
        val tokens = register()

        client
            .get()
            .uri("/test/protected")
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .exchange()
            .expectStatus()
            .isOk()
    }

    @Test
    fun `protected endpoint without token is rejected`() {
        client
            .get()
            .uri("/test/protected")
            .exchange()
            .expectStatus()
            .isForbidden()
    }

    private fun register(): AuthTokens {
        val tokens =
            client
                .post()
                .uri("/auth/credentials/register")
                .contentType(MediaType.APPLICATION_JSON)
                .body(authRequest())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(AuthTokens::class.java)
                .returnResult()
                .responseBody
        return assertNotNull(tokens)
    }

    private fun authRequest(
        email: String = "user@test.com",
        password: String = "Test123!@",
    ) = mapOf("email" to email, "password" to password)
}
