package com.dawidpawliczek.app.order

import com.dawidpawliczek.app.adapter.InMemoryCommandLog
import com.dawidpawliczek.engine.ports.CommandLog
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile

@TestConfiguration
class TestCommandLogConfig {

    @Bean
    @Profile("test")
    fun commandLog(): CommandLog = InMemoryCommandLog()
}
