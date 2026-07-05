package com.dawidpawliczek.engine.application;

import com.dawidpawliczek.engine.ports.CommandLog;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class RecordingCommandLog implements CommandLog {

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

    synchronized int size() {
        return entries.size();
    }

    synchronized RecordingCommandLog copy() {
        var copy = new RecordingCommandLog();
        for (byte[] entry : entries) {
            copy.entries.add(entry.clone());
        }
        return copy;
    }
}
