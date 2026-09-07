package com.dawidpawliczek.app.marketData.adapter.inbound.rest

import com.dawidpawliczek.app.marketData.application.model.Candle
import com.dawidpawliczek.app.marketData.application.port.inbound.GetCandles
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/marketdata")
class CandleController(
    private val getCandles: GetCandles,
) {
    @GetMapping("/candles")
    fun candles(
        @RequestParam(required = false) from: Long?,
        @RequestParam(required = false) to: Long?,
    ): List<Candle> = getCandles.candles(from, to)
}
