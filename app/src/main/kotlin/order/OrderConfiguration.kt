package com.dawidpawliczek.app.order

import com.dawidpawliczek.app.adapter.FileCommandLog
import com.dawidpawliczek.app.adapter.InMemoryCommandLog
import com.dawidpawliczek.engine.application.OrderService
import com.dawidpawliczek.engine.ports.CommandLog
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

@Configuration
class OrderConfiguration {
    @Bean
    @ConditionalOnProperty(name = ["exchange.commandlog"], havingValue = "file", matchIfMissing = true)
    fun fileCommandLog(
        @Value("\${exchange.commandlog.path:journal.bin}") path: String,
    ): CommandLog = FileCommandLog(Path.of(path))

    @Bean
    @ConditionalOnProperty(name = ["exchange.commandlog"], havingValue = "memory")
    fun inMemoryCommandLog(): CommandLog = InMemoryCommandLog()

    @Bean
    fun orderService(commandLog: CommandLog) = OrderService(commandLog)
}
