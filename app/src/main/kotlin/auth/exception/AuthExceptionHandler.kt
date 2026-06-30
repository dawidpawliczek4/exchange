package com.dawidpawliczek.app.auth.exception

import org.springframework.http.HttpStatus
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

data class AuthError(
    val message: String,
)

@RestControllerAdvice
class AuthExceptionHandler {
    @ExceptionHandler(EmailAlreadyTakenException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleEmailAlreadyTaken(e: EmailAlreadyTakenException) = AuthError(e.message)

    @ExceptionHandler(InvalidCredentialsException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleInvalidCredentials(e: InvalidCredentialsException) = AuthError(e.message)

    @ExceptionHandler(InvalidRefreshTokenException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleInvalidRefreshToken(e: InvalidRefreshTokenException) = AuthError(e.message)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleValidation(e: MethodArgumentNotValidException): AuthError {
        val message =
            e.bindingResult.fieldErrors
                .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        return AuthError(message)
    }
}
