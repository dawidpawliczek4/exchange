package com.dawidpawliczek.app.auth.credentials

import com.dawidpawliczek.app.auth.exception.AuthValidationCodes
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CredentialsRegisterRequest(
    @field:NotBlank(AuthValidationCodes.EMAIL_REQUIRED)
    @field:Email(AuthValidationCodes.EMAIL_INVALID)
    val email: String,
    @field:NotBlank(AuthValidationCodes.PASSWORD_REQUIRED)
    @field:Size(AuthValidationCodes.PASSWORD_LENGTH_INVALID, min = 8, max = 72)
    val password: String,
) {
    val normalizedEmail: String
        get() = email.lowercase().trim()
}

data class CredentialsLoginRequest(
    @field:NotBlank(AuthValidationCodes.EMAIL_REQUIRED)
    @field:Email(AuthValidationCodes.EMAIL_INVALID)
    val email: String,
    @field:NotBlank(AuthValidationCodes.PASSWORD_REQUIRED)
    val password: String,
) {
    val normalizedEmail: String
        get() = email.lowercase().trim()
}
