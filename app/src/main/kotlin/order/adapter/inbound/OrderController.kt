package com.dawidpawliczek.app.order.adapter.inbound

import com.dawidpawliczek.app.order.adapter.inbound.dto.ErrorResponse
import com.dawidpawliczek.app.order.adapter.inbound.dto.OrderRequest
import com.dawidpawliczek.app.order.application.port.inbound.OrderUseCase
import com.dawidpawliczek.contracts.CancelOrderCommand
import com.dawidpawliczek.contracts.PlaceOrderCommand
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/order")
class OrderController(
    private val orderService: OrderUseCase,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun postOrder(
        @AuthenticationPrincipal userId: Long,
        @RequestBody orderRequest: OrderRequest,
    ) = orderService.placeOrder(
        PlaceOrderCommand(
            userId,
            orderRequest.side,
            orderRequest.price,
            orderRequest.market,
            orderRequest.quantity,
        ),
    )

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun cancelOrder(
        @AuthenticationPrincipal userId: Long,
        @PathVariable id: Long,
    ) = orderService.cancelOrder(
        CancelOrderCommand(
            userId,
            id,
        ),
    )

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidOrder(e: IllegalArgumentException) = ErrorResponse(e.message ?: "Invalid request")
}
