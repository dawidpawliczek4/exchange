package com.dawidpawliczek.app.auth.credentials

import com.dawidpawliczek.app.auth.AuthTokens
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth/credentials")
class CredentialsController(
    private val credentialsService: CredentialsService,
) {
    @PostMapping("/register")
    fun register(
        @Valid @RequestBody request: CredentialsRegisterRequest,
    ): AuthTokens = credentialsService.register(request)

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: CredentialsLoginRequest,
    ): AuthTokens = credentialsService.login(request)
}
