package com.dawidpawliczek.app.auth.exception

object AuthValidationCodes {
    const val EMAIL_REQUIRED = "Email is required"
    const val EMAIL_INVALID = "Email is invalid"
    const val PASSWORD_REQUIRED = "Password is required"
    const val PASSWORD_LENGTH_INVALID = "Password must be between 8 and 72 characters"
}
