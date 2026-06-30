package com.dawidpawliczek.app.auth.credentials

import com.dawidpawliczek.app.auth.AuthTokens
import com.dawidpawliczek.app.auth.exception.EmailAlreadyTakenException
import com.dawidpawliczek.app.auth.exception.InvalidCredentialsException
import com.dawidpawliczek.app.auth.session.SessionService
import com.dawidpawliczek.app.auth.user.User
import com.dawidpawliczek.app.auth.user.UserRepository
import jakarta.transaction.Transactional
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class CredentialsService(
    private val credentialsRepository: CredentialsRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val sessionService: SessionService,
) {
    @Transactional
    fun register(request: CredentialsRegisterRequest): AuthTokens {
        if (credentialsRepository.existsByEmail(request.normalizedEmail)) {
            throw EmailAlreadyTakenException()
        }

        val user = userRepository.save(User())

        credentialsRepository.save(
            Credentials(
                email = request.normalizedEmail,
                passwordHash = passwordEncoder.encode(request.password)!!,
                user = user,
            ),
        )

        return sessionService.issueTokens(user)
    }

    @Transactional
    fun login(request: CredentialsLoginRequest): AuthTokens {
        val credentials =
            credentialsRepository.findByEmail(request.normalizedEmail)
                ?: throw InvalidCredentialsException()

        if (!passwordEncoder.matches(request.password, credentials.passwordHash)) {
            throw InvalidCredentialsException()
        }

        return sessionService.issueTokens(credentials.user)
    }
}
