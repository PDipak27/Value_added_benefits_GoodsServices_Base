package com.vab.events.order;

import io.eventuate.Event;
import java.time.Instant;

public class OrderConfirmed implements Event {
    private Instant confirmedAt;

    public OrderConfirmed() {}

    public OrderConfirmed(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public Instant getConfirmedAt() { return confirmedAt; }
}
