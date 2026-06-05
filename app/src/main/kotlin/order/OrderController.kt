package com.dawidpawliczek.app.order

import com.dawidpawliczek.engine.Order
import com.dawidpawliczek.engine.OrderBook
import com.dawidpawliczek.engine.Side
import com.dawidpawliczek.engine.Trade
import net.bytebuddy.ByteBuddy
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

@RestController
@RequestMapping("/order")
class OrderController(
    private val orderBook: OrderBook,
    private val journalPath: Path = Path.of("journal.bin"),
) {

    private val counter = AtomicLong(0)
    private val transactionHistory = CopyOnWriteArrayList<Trade>()
    private val lock = Any()

    init {
        recover()
    }

    @PostMapping
    fun postOrder(
        @RequestBody orderRequest: OrderRequest
    ): List<Trade> = synchronized(lock) {
        val order = Order(
            counter.getAndIncrement(),
            orderRequest.userId,
            orderRequest.side,
            orderRequest.price,
            orderRequest.market,
            orderRequest.quantity
        )
        try {
            submitToJournal(order, journalPath)
        } catch (e: Exception) {
            println("ERROR: ${e.message} ${e.cause}")
        }
        val trades = orderBook.submit(order)
        transactionHistory.addAll(trades)
        return trades;
    }

    @GetMapping
    fun getOrderBook() = transactionHistory

    private fun recover() {
        val orders = readJournal(journalPath)
        var maxId = -1L
        for (order in orders) {
            val trades = orderBook.submit(order)
            transactionHistory.addAll(trades)
            maxId = maxOf(maxId, order.id())
        }
        counter.set(maxId + 1)
    }
}

fun submitToJournal(order: Order, path: Path) {
    val ch = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)

    ch.write(order.serializeToByteBuffer())
    ch.force(false)
}

fun readJournal(path: Path): List<Order> {
    if (Files.notExists(path)) return emptyList()   // pierwszy start, brak journala

    val orders = mutableListOf<Order>()

    FileChannel.open(path, StandardOpenOption.READ).use { ch ->
        val headerBuffer = ByteBuffer.allocate(8)

        while (true) {
            headerBuffer.clear()
            if (!ch.readFully(headerBuffer)) break       // <8 bajtów = urwany ogon → koniec replayu

            headerBuffer.flip()
            val payloadLength = headerBuffer.getInt()
            val crc32 = headerBuffer.getInt() // todo: weryfikacja

            val payloadBuffer = ByteBuffer.allocate(payloadLength)
            if (!ch.readFully(payloadBuffer)) break      // urwany payload = crash w trakcie zapisu → koniec

            payloadBuffer.flip()
            val id = payloadBuffer.getLong()
            val userId = payloadBuffer.getLong()
            val sideByte = payloadBuffer.get()
            val price = payloadBuffer.getLong()
            val isMarketByte = payloadBuffer.get()
            val quantity = payloadBuffer.getLong()

            val side = if (sideByte == 0.toByte()) Side.SELL else Side.BUY
            val isMarket = isMarketByte == 1.toByte()

            orders.add(Order(id, userId, side, price, isMarket, quantity))
        }
    }

    return orders
}

private fun FileChannel.readFully(buf: ByteBuffer): Boolean {
    while (buf.hasRemaining()) {
        if (read(buf) == -1) return false
    }
    return true
}

fun Order.serializeToByteBuffer(): ByteBuffer {
//    [ int length ][ int crc32 ][ payload (length bajtów) ]
    val payloadLength = 8 + 8 + 1 + 8 + 1 + 8
    val totalFrameSize = 4 + 4 + payloadLength

    val buffer = ByteBuffer.allocate(totalFrameSize)

    buffer.putInt(payloadLength)
    buffer.putInt(0) // TODO

    buffer.putLong(this.id())
    buffer.putLong(this.userId())
    buffer.put(if (this.side() == Side.SELL) 0 else 1)
    buffer.putLong(this.price())
    buffer.put(if (this.isMarket) 1 else 0)
    buffer.putLong(this.quantity())

    buffer.flip()
    return buffer
}


data class OrderRequest(
    val userId: Long,
    val side: Side,
    val price: Long,
    val market: Boolean,
    val quantity: Long
)
