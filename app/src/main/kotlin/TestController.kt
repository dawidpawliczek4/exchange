package com.dawidpawliczek.app

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/test")
class TestController {

    @GetMapping("/health-test")
    fun healthTest() = mapOf(
        "status" to "ok",
        "service" to "exchange",
        "message" to "Running!"
    )
}