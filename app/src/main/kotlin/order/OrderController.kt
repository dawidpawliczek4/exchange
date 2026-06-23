package com.dawidpawliczek.app.order

import com.dawidpawliczek.engine.application.OrderService
import com.dawidpawliczek.engine.application.PlaceOrderCommand
import com.dawidpawliczek.engine.domain.Side
import com.dawidpawliczek.engine.domain.Trade
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CompletableFuture

@RestController
@RequestMapping("/order")
class OrderController(
    private val orderService: OrderService,
) {
    @PostMapping
    fun postOrder(
        @RequestBody orderRequest: OrderRequest,
    ): CompletableFuture<List<Trade>> {
        validate(orderRequest)
        return orderService.place(
            PlaceOrderCommand(
                orderRequest.userId,
                orderRequest.side,
                orderRequest.price,
                orderRequest.market,
                orderRequest.quantity,
            ),
        )
    }

    @GetMapping
    fun getOrderBook(): List<Trade> = orderService.history()

    private fun validate(orderRequest: OrderRequest) {
        if (orderRequest.price <= 0 || orderRequest.quantity <= 0) {
            throw IllegalArgumentException("Invalid request")
        }
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidOrder(e: IllegalArgumentException) = ErrorResponse(e.message ?: "Invalid request")
}

data class OrderRequest(
    val userId: Long,
    val side: Side,
    val price: Long,
    val market: Boolean,
    val quantity: Long,
)

data class ErrorResponse(
    val message: String,
)
