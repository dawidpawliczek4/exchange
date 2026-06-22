package com.dawidpawliczek.app.adapter

import com.dawidpawliczek.engine.ports.CommandLog
import java.util.function.Consumer

class InMemoryCommandLog : CommandLog {
    private val entries = ArrayList<ByteArray>()

    @Synchronized
    override fun append(payload: ByteArray) {
        entries.add(payload.copyOf())
    }

    override fun sync() { }

    @Synchronized
    override fun replay(handler: Consumer<ByteArray>) {
        entries.forEach { handler.accept(it.copyOf()) }
    }
}
