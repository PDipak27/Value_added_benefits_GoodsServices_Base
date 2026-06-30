package com.vab.order.query.api;

import com.vab.order.query.document.OrderSearchView;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic ops search over {@code order_search_v1} (§B3). Builds a Mongo query from
 * the optional filters (status, offerCode, placedAt range), newest first, capped.
 */
@Service
public class OrderSearchService {

    private final MongoTemplate mongo;

    public OrderSearchService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public List<OrderSearchView> search(String status, String offerCode,
                                        Instant from, Instant to, int limit) {
        List<Criteria> filters = new ArrayList<>();
        if (StringUtils.hasText(status))    filters.add(Criteria.where("status").is(status));
        if (StringUtils.hasText(offerCode)) filters.add(Criteria.where("offerCode").is(offerCode));
        if (from != null || to != null) {
            Criteria placed = Criteria.where("placedAt");
            if (from != null) placed = placed.gte(from);
            if (to != null)   placed = placed.lte(to);
            filters.add(placed);
        }

        Query query = new Query();
        if (!filters.isEmpty()) {
            query.addCriteria(new Criteria().andOperator(filters.toArray(new Criteria[0])));
        }
        query.with(Sort.by(Sort.Direction.DESC, "placedAt")).limit(limit);
        return mongo.find(query, OrderSearchView.class);
    }
}
