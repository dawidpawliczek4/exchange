package com.dawidpawliczek.matching.adapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class FileCommandLogTest {
    @TempDir
    lateinit var dir: Path

    private fun journal(): Path = dir.resolve("journal.bin")

    private fun payload(seed: Int): ByteArray = ByteArray(16) { (seed + it).toByte() }

    private fun replayAll(path: Path): List<ByteArray> {
        val log = FileCommandLog(path)
        val entries = mutableListOf<ByteArray>()
        log.replay { entries.add(it) }
        log.close()
        return entries
    }

    @Test
    fun appendAndReplayRoundTrip() {
        val log = FileCommandLog(journal())
        val payloads = listOf(payload(0), payload(50), payload(100))
        payloads.forEach { log.append(it) }
        log.sync()
        log.close()

        val replayed = replayAll(journal())
        assertEquals(3, replayed.size)
        payloads.zip(replayed).forEach { (expected, actual) -> assertEquals(expected.toList(), actual.toList()) }
    }

    @Test
    fun replayStopsAtCorruptedFrame() {
        val log = FileCommandLog(journal())
        repeat(3) { log.append(payload(it * 50)) }
        log.sync()
        log.close()

        val frameSize = 8L + 16L
        val thirdPayloadStart = 2 * frameSize + 8
        FileChannel.open(journal(), StandardOpenOption.WRITE).use { ch ->
            ch.write(ByteBuffer.wrap(byteArrayOf(-1)), thirdPayloadStart)
        }

        val replayed = replayAll(journal())
        assertEquals(2, replayed.size)
        assertEquals(payload(0).toList(), replayed[0].toList())
        assertEquals(payload(50).toList(), replayed[1].toList())
    }

    @Test
    fun replayStopsAtTruncatedTail() {
        val log = FileCommandLog(journal())
        repeat(3) { log.append(payload(it * 50)) }
        log.sync()
        log.close()

        val frameSize = 8L + 16L
        FileChannel.open(journal(), StandardOpenOption.WRITE).use { ch ->
            ch.truncate(2 * frameSize + 5)
        }

        val replayed = replayAll(journal())
        assertEquals(2, replayed.size)
    }

    @Test
    fun syncCloseReopenReplays() {
        val log = FileCommandLog(journal())
        repeat(3) { log.append(payload(it * 50)) }
        log.sync()
        log.close()

        val reopened = FileCommandLog(journal())
        reopened.append(payload(200))
        reopened.sync()
        reopened.close()

        val replayed = replayAll(journal())
        assertEquals(4, replayed.size)
        assertEquals(payload(200).toList(), replayed[3].toList())
        assertEquals(4L * (8 + 16), Files.size(journal()))
    }
}
