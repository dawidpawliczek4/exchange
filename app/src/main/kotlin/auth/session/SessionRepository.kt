package com.dawidpawliczek.app.auth.session

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface SessionRepository : JpaRepository<Session, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findLockedByTokenHash(tokenHash: String): Session?
}
