package com.dawidpawliczek.app.marketData.adapter.outbound.websocket

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig : WebSocketConfigurer {
    @Bean
    fun marketDataBroadcaster() = WebSocketBroadcaster()

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry
            .addHandler(marketDataBroadcaster(), "/marketdata")
            .setAllowedOrigins("*")
    }
}
