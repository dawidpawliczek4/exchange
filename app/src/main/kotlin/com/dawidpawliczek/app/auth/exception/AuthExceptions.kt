package com.dawidpawliczek.app.auth.exception

import org.springframework.http.HttpStatus

sealed class AuthException(
    val status: HttpStatus,
    override val message: String,
) : RuntimeException(message)

class EmailAlreadyTakenException : AuthException(HttpStatus.CONFLICT, "Email already taken")

class InvalidCredentialsException : AuthException(HttpStatus.UNAUTHORIZED, "Invalid credentials")

class InvalidRefreshTokenException : AuthException(HttpStatus.UNAUTHORIZED, "Invalid refresh token")
