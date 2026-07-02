package com.dawidpawliczek.app.order.application.port.inbound

import com.dawidpawliczek.contracts.PlaceOrderCommand

interface PlaceOrderUseCase {
    fun placeOrder(command: PlaceOrderCommand)
}
