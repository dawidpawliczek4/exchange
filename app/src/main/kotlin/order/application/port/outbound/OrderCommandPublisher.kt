package com.dawidpawliczek.app.order.application.port.outbound

import com.dawidpawliczek.contracts.PlaceOrderCommand

interface OrderCommandPublisher {
    fun publish(command: PlaceOrderCommand)
}
