package com.dawidpawliczek.app.order

import com.dawidpawliczek.contracts.PlaceOrderCommand
import com.dawidpawliczek.contracts.Side
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/order")
class OrderController(
    private val publisher: OrderCommandPublisher,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun postOrder(
        @AuthenticationPrincipal userId: Long,
        @RequestBody orderRequest: OrderRequest,
    ) {
        validate(orderRequest)
        publisher.publish(
            PlaceOrderCommand(
                userId,
                orderRequest.side,
                orderRequest.price,
                orderRequest.market,
                orderRequest.quantity,
            ),
        )
    }

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
    val side: Side,
    val price: Long,
    val market: Boolean,
    val quantity: Long,
)

data class ErrorResponse(
    val message: String,
)
