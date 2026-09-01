package com.dawidpawliczek.app.order.application.port.outbound

import com.dawidpawliczek.contracts.command.OrderCommand

interface OrderCommandPublisher {
    fun publish(command: OrderCommand)
}
