package com.dawidpawliczek.app.order

import com.dawidpawliczek.engine.OrderBook
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OrderConfiguration {

    @Bean
    fun orderBook() = OrderBook()
}
