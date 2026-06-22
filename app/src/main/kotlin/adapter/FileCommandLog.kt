package com.dawidpawliczek.app.adapter

import com.dawidpawliczek.engine.ports.CommandLog
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.function.Consumer
import java.util.zip.CRC32

class FileCommandLog(
    private val path: Path = Path.of("journal.bin"),
) : CommandLog {
    private val channel: FileChannel =
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        )

    @Synchronized
    override fun append(payload: ByteArray) {
        val frame = ByteBuffer.allocate(4 + 4 + payload.size)
        frame.putInt(payload.size)
        frame.putInt(crc(payload))
        frame.put(payload)
        frame.flip()

        while (frame.hasRemaining()) channel.write(frame)
    }

    @Synchronized
    override fun sync() {
        channel.force(false)
    }

    override fun replay(handler: Consumer<ByteArray>) {
        if (Files.notExists(path)) return

        FileChannel.open(path, StandardOpenOption.READ).use { ch ->
            val header = ByteBuffer.allocate(8)
            while (true) {
                header.clear()
                if (!ch.readFully(header)) break

                header.flip()
                val length = header.getInt()
                val expectedCrc = header.getInt()

                val payloadBuf = ByteBuffer.allocate(length)
                if (!ch.readFully(payloadBuf)) break

                val payload = payloadBuf.array()
                if (crc(payload) != expectedCrc) break

                handler.accept(payload)
            }
        }
    }

    fun close() = channel.close()

    private fun crc(data: ByteArray): Int {
        val crc = CRC32()
        crc.update(data)
        return crc.value.toInt()
    }

    private fun FileChannel.readFully(buf: ByteBuffer): Boolean {
        while (buf.hasRemaining()) {
            if (read(buf) == -1) return false
        }
        return true
    }
}
