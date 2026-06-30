package com.vab.order.query.config;

import com.vab.order.query.document.OrderSearchView;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

/**
 * Ops-search indexes (§B3): non-unique secondary indexes on the order_search_v1
 * fields the ops dashboard filters by. Created explicitly because Boot disables
 * Mongo auto-index creation.
 */
@Configuration
public class OrderSearchIndexConfig {

    private final MongoTemplate mongoTemplate;

    public OrderSearchIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        var ops = mongoTemplate.indexOps(OrderSearchView.class);
        ops.ensureIndex(new Index().on("status", Sort.Direction.ASC));
        ops.ensureIndex(new Index().on("offerCode", Sort.Direction.ASC));
        ops.ensureIndex(new Index().on("placedAt", Sort.Direction.DESC));
    }
}
