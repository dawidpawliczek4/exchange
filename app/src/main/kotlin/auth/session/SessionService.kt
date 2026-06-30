package com.dawidpawliczek.app.auth.session

import com.dawidpawliczek.app.auth.AuthTokens
import com.dawidpawliczek.app.auth.JwtService
import com.dawidpawliczek.app.auth.exception.InvalidRefreshTokenException
import com.dawidpawliczek.app.auth.user.User
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date

@Service
class SessionService(
    private val sessionRepository: SessionRepository,
    private val jwtService: JwtService,
    @param:Value("\${jwt.refresh-ttl-seconds}") private val refreshTtl: Long,
) {
    private val secureRandom = SecureRandom()

    @Transactional
    fun issueTokens(user: User): AuthTokens {
        val accessToken = jwtService.issueAccessToken(user)
        val refreshToken = generateRefreshToken()
        val refreshTokenHash = hashRefreshToken(refreshToken)

        sessionRepository.save(
            Session(
                tokenHash = refreshTokenHash,
                expiresAt = Date(System.currentTimeMillis() + refreshTtl * 1000).toInstant(),
                user = user,
            ),
        )

        return AuthTokens(
            accessToken,
            refreshToken,
        )
    }

    @Transactional
    fun refresh(refreshToken: String): AuthTokens {
        val hash = hashRefreshToken(refreshToken)

        val storedToken =
            sessionRepository.findLockedByTokenHash(hash)
                ?: throw InvalidRefreshTokenException()

        if (storedToken.expiresAt.isBefore(Instant.now())) {
            sessionRepository.delete(storedToken)
            throw InvalidRefreshTokenException()
        }

        sessionRepository.delete(storedToken)
        return issueTokens(storedToken.user)
    }

    @Transactional
    fun logout(refreshToken: String) {
        val hash = hashRefreshToken(refreshToken)
        sessionRepository.findLockedByTokenHash(hash)?.let { sessionRepository.delete(it) }
    }

    private fun generateRefreshToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashRefreshToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(digest.digest(token.toByteArray(StandardCharsets.UTF_8)))
    }
}
