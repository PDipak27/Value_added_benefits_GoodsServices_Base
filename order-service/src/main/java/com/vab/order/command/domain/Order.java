package com.vab.order.command.domain;

import com.vab.events.order.OrderConfirmed;
import com.vab.events.order.OrderFailed;
import com.vab.events.order.OrderPlaced;
import io.eventuate.Event;
import io.eventuate.ReflectiveMutableCommandProcessingAggregate;

import java.time.Instant;
import java.util.List;

/**
 * Order aggregate — event-sourced via Eventuate Local.
 *
 * Pattern: process(Command) → List<Event>  (decides what happened)
 *          apply(Event)     → void         (mutates state from that event)
 *
 * ReflectiveMutableCommandProcessingAggregate wires these by reflection,
 * matching method signatures — no explicit registration needed.
 */
public class Order extends ReflectiveMutableCommandProcessingAggregate<Order, OrderCommand> {

    private String       subscriberId;
    private String       offerCode;
    private long         amount;
    private OrderStatus  status;

    // ── Command handlers ─────────────────────────────────────────────────

    public List<Event> process(PlaceOrderCommand cmd) {
        return List.of(new OrderPlaced(
                cmd.getSubscriberId(),
                cmd.getOfferCode(),
                cmd.getPriceSnapshotId(),
                cmd.getAmount(),
                cmd.getCurrency(),
                cmd.getBillingMode(),
                cmd.getIdempotencyKey()
        ));
    }

    public List<Event> process(ConfirmOrderCommand cmd) {
        return List.of(new OrderConfirmed(Instant.now()));
    }

    public List<Event> process(FailOrderCommand cmd) {
        return List.of(new OrderFailed(cmd.getFailedStep(), cmd.getReason()));
    }

    // ── Event appliers ────────────────────────────────────────────────────

    public void apply(OrderPlaced event) {
        this.subscriberId = event.getSubscriberId();
        this.offerCode    = event.getOfferCode();
        this.amount       = event.getAmount();
        this.status       = OrderStatus.PLACED;
    }

    public void apply(OrderConfirmed event) {
        this.status = OrderStatus.CONFIRMED;
    }

    public void apply(OrderFailed event) {
        this.status = OrderStatus.FAILED;
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    public String      getSubscriberId() { return subscriberId; }
    public String      getOfferCode()    { return offerCode; }
    public long        getAmount()       { return amount; }
    public OrderStatus getStatus()       { return status; }
}
