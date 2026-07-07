package com.dawidpawliczek.app.order.application.port.inbound

import com.dawidpawliczek.contracts.CancelOrderCommand
import com.dawidpawliczek.contracts.PlaceOrderCommand

interface OrderUseCase {
    fun placeOrder(command: PlaceOrderCommand)

    fun cancelOrder(command: CancelOrderCommand)
}
