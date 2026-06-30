package com.vab.ott.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A streamable title in the OTT catalog (§A-2). {@code offerCode} ties the video to
 * the VA-BAGS offer that grants it — streaming is gated on the subscriber holding an
 * ACTIVE entitlement for that offer. (No real media; the stream endpoint returns a
 * "Playing video: …" success message.)
 */
@Entity
@Table(name = "videos", schema = "ott")
public class Video {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** The offer whose entitlement unlocks this video. */
    @Column(name = "offer_code", nullable = false, length = 100)
    private String offerCode;

    protected Video() {}

    public String getId()        { return id; }
    public String getTitle()     { return title; }
    public String getOfferCode() { return offerCode; }
}
