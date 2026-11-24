package com.vab.fulfilment.command;

import com.vab.events.common.ProductType;
import com.vab.events.fulfilment.FulfilOrderCommand;
import com.vab.events.fulfilment.OrderFulfilled;
import com.vab.fulfilment.domain.FulfilmentRecord;
import com.vab.fulfilment.ott.OttClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * DIGITAL_SUBSCRIPTION provisioning (Design/13 §6): the single OTT attempt shared by the fulfil handler
 * (first attempt) and the provisioning poller (later attempts).
 *
 * <p>{@link #attempt} only talks HTTP. It is deliberately <b>not</b> {@code @Transactional} and writes
 * nothing; whoever applies the outcome writes the {@link FulfilmentRecord} and the reply in its own
 * transaction. The validity window starts at the attempt, so a subscription provisioned after a long
 * OTT outage starts when it was actually provisioned.
 */
@Service
public class OttProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(OttProvisioningService.class);

    /** Outcome of one attempt, with what's needed to build the reply. */
    public record Attempt(OttClient.Result result, String orderId, Instant validFrom, Instant validUntil) {

        public OttClient.Outcome outcome()  { return result.outcome(); }
        public String externalRef()         { return result.externalRef(); }
        public String reason()              { return result.reason(); }
        public String detail()              { return result.detail(); }
        /** OTT may have created the entitlement (success, or a call whose fate is unknown). */
        public boolean mayHaveProvisioned() { return result.uncertain(); }

        public FulfilmentRecord toRecord(String fulfilmentRef) {
            return new FulfilmentRecord(fulfilmentRef, orderId, ProductType.DIGITAL_SUBSCRIPTION,
                    null, null, externalRef());
        }

        public OrderFulfilled toOrderFulfilled(String fulfilmentRef) {
            Instant from  = result.validFrom() != null ? result.validFrom() : validFrom;
            Instant until = result.validFrom() != null ? result.validUntil() : validUntil;
            return new OrderFulfilled(orderId, ProductType.DIGITAL_SUBSCRIPTION.name(), fulfilmentRef,
                    null, null, externalRef(), from, until);
        }
    }

    private final OttClient ott;

    public OttProvisioningService(OttClient ott) {
        this.ott = ott;
    }

    public Attempt attempt(FulfilOrderCommand cmd) {
        Instant from  = Instant.now();
        Instant until = cmd.getTermMonths() == null ? null
                : from.atOffset(ZoneOffset.UTC).plusMonths(cmd.getTermMonths()).toInstant();
        OttClient.Result r = ott.provision(cmd.getOrderId(), cmd.getSubscriberId(), cmd.getOfferCode(), from, until);
        return new Attempt(r, cmd.getOrderId(), from, until);
    }

    public static String newFulfilmentRef() {
        return "ent_" + UUID.randomUUID();
    }

    /** Admin revoke (Phase 3): delegate to OTT DELETE. Returns false on any error. */
    public boolean revoke(String externalRef) {
        boolean ok = ott.revoke(externalRef);
        log.info("OTT entitlement revoke: externalRef={}, ok={}", externalRef, ok);
        return ok;
    }

    /** Revoke whatever OTT holds for the order (refund path). False on any error. */
    public boolean revokeByOrder(String orderId) {
        return ott.revokeByOrder(orderId);
    }

    public boolean isOttCallPermitted() {
        return ott.isCallPermitted();
    }
}
