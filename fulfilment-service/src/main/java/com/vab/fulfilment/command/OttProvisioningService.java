package com.vab.fulfilment.command;

import com.vab.events.common.ProductType;
import com.vab.fulfilment.domain.FulfilmentRecord;
import com.vab.fulfilment.domain.FulfilmentRecordRepository;
import com.vab.fulfilment.ott.OttClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * DIGITAL_SUBSCRIPTION provisioning (DD-27) — the single code path shared by the
 * saga's Tram fulfil handler and the admin re-drive REST endpoint. It calls the
 * external {@code ott-service} via {@link OttClient} (bounded retries inside) and,
 * on success, writes the auditable {@link FulfilmentRecord}. OTT is idempotent on
 * {@code orderId}, so a re-drive returns the same {@code externalRef}.
 */
@Service
public class OttProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(OttProvisioningService.class);

    /** Provisioning outcome surfaced to both callers. */
    public record ProvisionResult(boolean provisioned, String fulfilmentRef,
                                  String externalRef, String reason, String detail) {}

    private final OttClient ott;
    private final FulfilmentRecordRepository records;

    public OttProvisioningService(OttClient ott, FulfilmentRecordRepository records) {
        this.ott     = ott;
        this.records = records;
    }

    @Transactional
    public ProvisionResult provision(String orderId, String subscriberId, String offerCode) {
        OttClient.Result r = ott.provision(orderId, subscriberId, offerCode);
        if (!r.provisioned()) {
            return new ProvisionResult(false, null, null, r.reason(), r.detail());
        }
        String fulfilmentRef = "ent_" + UUID.randomUUID();
        records.save(new FulfilmentRecord(fulfilmentRef, orderId,
                ProductType.DIGITAL_SUBSCRIPTION, null, null, r.externalRef()));
        log.info("Provisioned DIGITAL_SUBSCRIPTION: orderId={}, entitlement={}, externalRef={}",
                orderId, fulfilmentRef, r.externalRef());
        return new ProvisionResult(true, fulfilmentRef, r.externalRef(), null, null);
    }
}
