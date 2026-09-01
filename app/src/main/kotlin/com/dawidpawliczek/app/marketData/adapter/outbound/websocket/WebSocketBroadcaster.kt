package com.dawidpawliczek.app.marketData.adapter.outbound.websocket

import com.dawidpawliczek.app.marketData.application.port.outbound.MarketDataBroadcaster
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.CopyOnWriteArraySet

@Component
class WebSocketBroadcaster :
    TextWebSocketHandler(),
    MarketDataBroadcaster {
    private val sessions = CopyOnWriteArraySet<WebSocketSession>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        sessions.add(session)
    }

    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) {
        sessions.remove(session)
    }

    fun subscriberCount(): Int = sessions.size

    override fun broadcast(json: String) {
        val msg = TextMessage(json)
        for (s in sessions) {
            if (s.isOpen) s.sendMessage(msg)
        }
    }
}
