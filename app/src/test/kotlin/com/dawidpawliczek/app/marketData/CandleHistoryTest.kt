package com.dawidpawliczek.app.marketData

import com.dawidpawliczek.app.TestcontainersConfiguration
import com.dawidpawliczek.app.marketData.application.model.Candle
import com.dawidpawliczek.app.marketData.application.port.inbound.RecordTrades
import com.dawidpawliczek.contracts.Trade
import com.dawidpawliczek.contracts.event.TradeEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.client.RestTestClient
import kotlin.test.assertEquals

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@Import(TestcontainersConfiguration::class)
class CandleHistoryTest {
    @Autowired
    lateinit var client: RestTestClient

    @Autowired
    lateinit var recordTrades: RecordTrades

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val bucket0 = 1_756_900_000_000L
    private val bucket1 = bucket0 + 5_000

    private val tape =
        listOf(
            trade(seq = 1, at = bucket0 + 1_000, price = 100, quantity = 2),
            trade(seq = 2, at = bucket0 + 2_000, price = 110, quantity = 1),
            trade(seq = 3, at = bucket0 + 3_000, price = 95, quantity = 3),
            trade(seq = 4, at = bucket1 + 1_000, price = 105, quantity = 4),
        )

    @BeforeEach
    fun resetTape() {
        jdbc.execute("DELETE FROM trades")
    }

    @Test
    fun aggregatesTradesIntoEpochAlignedBuckets() {
        record(tape)

        val candles = candles(from = bucket0, to = bucket1 + 5_000)

        assertEquals(
            listOf(
                Candle(bucket0, open = 100, high = 110, low = 95, close = 95, volume = 6, quoteVolume = 595, tradeCount = 3),
                Candle(bucket1, open = 105, high = 105, low = 105, close = 105, volume = 4, quoteVolume = 420, tradeCount = 1),
            ),
            candles,
        )
    }

    @Test
    fun ignoresRedeliveredTrades() {
        record(tape)
        record(tape)

        val candles = candles(from = bucket0, to = bucket1 + 5_000)

        assertEquals(listOf(3L, 1L), candles.map { it.tradeCount })
    }

    @Test
    fun boundsTheRangeByBucketStart() {
        record(tape)

        val candles = candles(from = bucket1, to = bucket1 + 5_000)

        assertEquals(listOf(bucket1), candles.map { it.bucketStart })
    }

    @Test
    fun rejectsInvertedRangeAsProblemDetail() {
        client
            .get()
            .uri("/marketdata/candles?from=$bucket1&to=$bucket0")
            .exchange()
            .expectStatus()
            .isBadRequest()
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
    }

    private fun record(events: List<TradeEvent>) {
        recordTrades.record(events)
        jdbc.execute("CALL refresh_continuous_aggregate('candles_5s', NULL, NULL)")
    }

    private fun candles(
        from: Long,
        to: Long,
    ): List<Candle> =
        client
            .get()
            .uri("/marketdata/candles?from=$from&to=$to")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(object : ParameterizedTypeReference<List<Candle>>() {})
            .returnResult()
            .responseBody!!

    private fun trade(
        seq: Long,
        at: Long,
        price: Long,
        quantity: Long,
    ) = TradeEvent(seq, at, Trade(seq, 1, seq + 100, 2, price, quantity))
}
