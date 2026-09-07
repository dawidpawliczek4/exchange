package com.dawidpawliczek.app

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class ProbeController {
    @GetMapping("/test/protected")
    fun protected(
        @AuthenticationPrincipal userId: Long,
    ): Map<String, Long> = mapOf("userId" to userId)

    @GetMapping("/test/boom")
    fun boom(): Nothing = throw IllegalStateException("shard 7 unreachable")
}
