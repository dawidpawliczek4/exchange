package com.dawidpawliczek.app.order

import com.dawidpawliczek.app.adapter.FileCommandLog
import com.dawidpawliczek.engine.ports.CommandLog
import com.dawidpawliczek.engine.application.OrderService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
class OrderConfiguration {

    @Bean
    @Profile("!test")
    fun commandLog(): CommandLog = FileCommandLog()

    @Bean
    fun orderService(commandLog: CommandLog) = OrderService(commandLog)
}
