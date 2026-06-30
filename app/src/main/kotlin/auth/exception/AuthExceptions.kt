package com.dawidpawliczek.app.auth.exception

sealed class AuthException(
    override val message: String,
) : RuntimeException(message)

class EmailAlreadyTakenException : AuthException("Email already taken")

class InvalidCredentialsException : AuthException("Invalid credentials")

class InvalidRefreshTokenException : AuthException("Invalid refresh token")
