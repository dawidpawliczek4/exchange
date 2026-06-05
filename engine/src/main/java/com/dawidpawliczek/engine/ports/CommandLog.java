package com.dawidpawliczek.engine.ports;

import java.util.function.Consumer;

public interface CommandLog {
    long append(byte[] payload);
    void replay(Consumer<byte[]> handler);
}
