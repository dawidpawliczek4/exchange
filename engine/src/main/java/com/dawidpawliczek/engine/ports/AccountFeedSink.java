package com.dawidpawliczek.engine.ports;

import com.dawidpawliczek.contracts.event.AccountEvent;
import java.util.List;

public interface AccountFeedSink {
    void publish(List<AccountEvent> events);
}
