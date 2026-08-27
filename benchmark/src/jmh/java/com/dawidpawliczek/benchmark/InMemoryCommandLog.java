package com.dawidpawliczek.benchmark;

import com.dawidpawliczek.engine.ports.CommandLog;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class InMemoryCommandLog implements CommandLog {

    private final List<byte[]> entries = new ArrayList<>();

    @Override
    public synchronized void append(byte[] payload) {
        entries.add(payload.clone());
    }

    @Override
    public void sync() {}

    @Override
    public synchronized void replay(Consumer<byte[]> handler) {
        for (byte[] entry : entries) {
            handler.accept(entry.clone());
        }
    }
}
