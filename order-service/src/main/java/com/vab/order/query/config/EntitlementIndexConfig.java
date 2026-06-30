package com.vab.order.query.config;

import com.vab.order.query.document.EntitlementView;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * Uniqueness defense layer 2: a partial unique index on
 * {@code entitlements_v1(subscriberId, offerCode)} filtered to status=ACTIVE — so
 * a subscriber can hold at most one ACTIVE entitlement per offer, while REVOKED
 * history is retained. Created explicitly because Boot disables Mongo auto-index
 * creation and {@code @Indexed} cannot express a partial filter.
 */
@Configuration
public class EntitlementIndexConfig {

    private final MongoTemplate mongoTemplate;

    public EntitlementIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        mongoTemplate.indexOps(EntitlementView.class).ensureIndex(
                new Index().on("subscriberId", Sort.Direction.ASC)
                        .on("offerCode", Sort.Direction.ASC)
                        .unique()
                        .partial(PartialIndexFilter.of(Criteria.where("status").is("ACTIVE"))));
    }
}
