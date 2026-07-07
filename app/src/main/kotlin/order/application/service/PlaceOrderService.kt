package com.dawidpawliczek.app.order.application.service

import com.dawidpawliczek.app.order.application.port.inbound.OrderUseCase
import com.dawidpawliczek.app.order.application.port.outbound.OrderCommandPublisher
import com.dawidpawliczek.contracts.CancelOrderCommand
import com.dawidpawliczek.contracts.PlaceOrderCommand
import org.springframework.stereotype.Service

@Service
class PlaceOrderService(
    private val publisher: OrderCommandPublisher,
) : OrderUseCase {
    override fun placeOrder(command: PlaceOrderCommand) {
        validate(command)
        publisher.publish(command)
    }

    private fun validate(orderRequest: PlaceOrderCommand) {
        if (orderRequest.price <= 0 || orderRequest.quantity <= 0) {
            throw IllegalArgumentException("Invalid request")
        }
    }

    override fun cancelOrder(command: CancelOrderCommand) {
        publisher.publish(command)
    }
}
