package com.dawidpawliczek.app.order.adapter.inbound.dto

import com.dawidpawliczek.contracts.Side

data class OrderRequest(
    val side: Side,
    val price: Long,
    val market: Boolean,
    val quantity: Long,
)
