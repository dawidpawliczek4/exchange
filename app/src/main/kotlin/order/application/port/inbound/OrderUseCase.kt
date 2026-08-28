package com.dawidpawliczek.app.order.application.port.inbound

import com.dawidpawliczek.contracts.command.CancelOrderCommand
import com.dawidpawliczek.contracts.command.PlaceOrderCommand

interface OrderUseCase {
    fun placeOrder(command: PlaceOrderCommand)

    fun cancelOrder(command: CancelOrderCommand)
}
