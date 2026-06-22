package com.dawidpawliczek.engine.ports;

import java.util.function.Consumer;

public interface CommandLog {
    void append(byte[] payload);

    void sync();

    void replay(Consumer<byte[]> handler);
}
