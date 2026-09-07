package com.dawidpawliczek.app.marketData.application.model

data class Candle(
    val bucketStart: Long,
    val open: Long,
    val high: Long,
    val low: Long,
    val close: Long,
    val volume: Long,
    val quoteVolume: Long,
    val tradeCount: Long,
)
