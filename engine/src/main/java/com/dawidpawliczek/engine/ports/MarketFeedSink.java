package com.dawidpawliczek.engine.ports;

import com.dawidpawliczek.contracts.Trade;
import java.util.List;

public interface MarketFeedSink {
    void publish(List<Trade> trades);
}
