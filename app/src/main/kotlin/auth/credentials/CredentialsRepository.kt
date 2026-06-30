package com.dawidpawliczek.app.auth.credentials

import org.springframework.data.jpa.repository.JpaRepository

interface CredentialsRepository : JpaRepository<Credentials, Long> {
    fun findByEmail(email: String): Credentials?

    fun existsByEmail(email: String): Boolean
}
