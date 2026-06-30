package com.dawidpawliczek.app.auth.session

import com.dawidpawliczek.app.auth.AuthTokens
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth/session")
class SessionController(
    private val sessionService: SessionService,
) {
    data class RefreshRequest(
        val refreshToken: String,
    )

    @PostMapping("/refresh")
    fun refresh(
        @RequestBody request: RefreshRequest,
    ): AuthTokens = sessionService.refresh(request.refreshToken)

    @PostMapping("/logout")
    fun logout(
        @RequestBody request: RefreshRequest,
    ): Unit = sessionService.logout(request.refreshToken)
}
