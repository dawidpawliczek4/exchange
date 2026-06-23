package com.dawidpawliczek.engine.ports;

import com.dawidpawliczek.engine.domain.Trade;
import java.util.List;

public interface MarketFeedSink {
    void publish(List<Trade> trades);
}
