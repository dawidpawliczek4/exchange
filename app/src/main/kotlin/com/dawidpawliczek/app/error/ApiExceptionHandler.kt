package com.dawidpawliczek.app.error

import com.dawidpawliczek.app.auth.exception.AuthException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(AuthException::class)
    fun handleAuth(e: AuthException): ProblemDetail = ProblemDetail.forStatusAndDetail(e.status, e.message)

    @ExceptionHandler(AuthenticationException::class)
    fun handleUnauthenticated(e: AuthenticationException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required")

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message ?: "Invalid request")

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        e: Exception,
        request: HttpServletRequest,
    ): ProblemDetail {
        logger.error("Unhandled exception for ${request.method} ${request.requestURI}", e)
        return ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val body = ex.body
        body.setProperty("errors", ex.bindingResult.fieldErrors.map { FieldError(it.field, it.defaultMessage.orEmpty()) })
        return handleExceptionInternal(ex, body, headers, status, request)
    }

    data class FieldError(
        val field: String,
        val message: String,
    )
}
