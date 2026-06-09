package com.vab.catalog.domain;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OfferRepository extends MongoRepository<Offer, String> {
    List<Offer> findByStatus(OfferStatus status);
}
