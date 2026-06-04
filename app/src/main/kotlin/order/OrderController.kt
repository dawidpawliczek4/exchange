package com.dawidpawliczek.app.order

import com.dawidpawliczek.engine.OrderBook
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/order")
class OrderController(
    private val orderBook: OrderBook
) {

    @PostMapping
    fun postOrder() {
        // TODO: extract to service



    }

}



