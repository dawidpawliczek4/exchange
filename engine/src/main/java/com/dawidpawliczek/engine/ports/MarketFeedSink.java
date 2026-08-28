package com.dawidpawliczek.engine.ports;

import com.dawidpawliczek.contracts.event.MarketEvent;
import java.util.List;

public interface MarketFeedSink {
    void publish(List<MarketEvent> events);
}
