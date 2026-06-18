package com.dawidpawliczek.app.order

import com.dawidpawliczek.engine.application.OrderService
import com.dawidpawliczek.engine.application.PlaceOrderCommand
import com.dawidpawliczek.engine.domain.Side
import com.dawidpawliczek.engine.domain.Trade
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/order")
class OrderController(
    private val orderService: OrderService,
) {
    @PostMapping
    fun postOrder(
        @RequestBody orderRequest: OrderRequest,
    ): List<Trade> =
        orderService.place(
            PlaceOrderCommand(
                orderRequest.userId,
                orderRequest.side,
                orderRequest.price,
                orderRequest.market,
                orderRequest.quantity,
            ),
        )

    @GetMapping
    fun getOrderBook(): List<Trade> = orderService.history()
}

data class OrderRequest(
    val userId: Long,
    val side: Side,
    val price: Long,
    val market: Boolean,
    val quantity: Long,
)
