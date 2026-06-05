package com.dawidpawliczek.app.adapter

import com.dawidpawliczek.engine.ports.CommandLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

class InMemoryCommandLog : CommandLog {

    private val entries = CopyOnWriteArrayList<ByteArray>()

    @Synchronized
    override fun append(payload: ByteArray): Long {
        val offset = entries.size.toLong()
        entries.add(payload.copyOf())
        return offset
    }

    override fun replay(handler: Consumer<ByteArray>) {
        entries.forEach { handler.accept(it.copyOf()) }
    }
}
