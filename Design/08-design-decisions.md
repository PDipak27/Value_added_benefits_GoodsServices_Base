# Design Decisions Report

Format: each decision records the problem, the options considered, the choice made, and the rationale. Ordered by architectural significance.

> **Scale envelope (the lens for every decision below):** ~50 order TPS, ~300 read RPS. This is a comfortable single-node Postgres workload, so **nothing here is justified by scaling**. Each pattern must earn its place on *capability* grounds — distributed transaction (Saga), durable replayable event log (Kafka), read-shape/availability (CQRS) — or it is cut. **DD-14/DD-15 are the post-review revision** that applies this lens and removes Event Sourcing.

---

## DD-01 — Depth over breadth (4-5 services, not 10+)

**Problem:** Portfolio projects typically go wide (many thin services) to show "microservices understanding."

**Choice:** 5 services with real internal complexity each.

**Rationale:** Shallow breadth demonstrates service decomposition syntax, not substance. A Saga orchestrator, a transactional-outbox aggregate, CQRS with two stores, and a real OIDC flow each require non-trivial design decisions. Five deep services signal more engineering judgment than twelve CRUD wrappers.

---

## DD-02 — Event Sourcing scoped to Order aggregate only  ⚠️ SUPERSEDED by DD-14

**Problem:** Where should ES be applied?

**Options:**
- A) ES everywhere (all aggregates)
- B) ES on Order only
- C) No ES, just domain events

**Original choice:** B. **Revised choice (DD-14):** C — *no ES anywhere; domain events via outbox.*

**Original rationale:** Inventory and Billing are Saga *participants* — their state is simple, append-is-not-required, and the operational overhead of ES (replay, snapshot, projection) would add noise without insight. Order state is legally/operationally significant: "prove when this subscriber canceled, what the price was at order time, what compensation steps ran." ES earns its keep only here.

**Why this flipped:** the design review (50 TPS / 300 RPS) showed the audit/temporal needs are fully met by the **Kafka event log + a small `order_status_history` table + the stored `price_snapshot_id`** — none of which require event-sourcing the *aggregate*. ES's permanent tax (event-schema evolution + upcasting forever, snapshots, eventual-consistency reads) bought nothing the cheaper option doesn't. See DD-14.

---

## DD-03 — Saga orchestration over choreography

**Problem:** Distributed transaction across Inventory, Billing, and OTT.

**Options:**
- A) Choreography (event-driven reaction chain)
- B) Orchestration (central coordinator)

**Choice:** B

**Rationale:** Three services, branching flows per benefit type (physical vs slot vs digital), and LIFO compensation logic. Choreography's "where did this order get stuck?" question becomes unanswerable at runtime. Orchestration gives one place for visibility, one place for compensation logic, and one place for timeout/retry policy. Choreography is elegant for 2-step pipelines.

---

## DD-04 — CQRS with separate physical stores

**Problem:** Order reads and writes have different access patterns, different SLAs, different schema needs.

**Options:**
- A) Single Postgres table, indexed for both
- B) CQRS, same Postgres (separate schema)
- C) CQRS, separate stores (Postgres write / MongoDB read)

**Choice:** C

**Rationale:** Option A collapses read and write concerns; schema evolution becomes a breaking change for both paths. Option B preserves the logical split but tempts JOIN-based shortcuts. Option C makes the split non-negotiable: write side is the order state + outbox, read side is denormalized documents shaped per query. MongoDB's document model is a natural fit for "one doc per screen." The physical separation also means projections can be rebuilt from the Kafka event log without touching the write store.

**Amendment (DD-15):** at 300 RPS the read store is **not** required for performance, so it is positioned as an *optimization*, not a hard dependency. `GET /v1/orders/{id}` falls back to a single-key Postgres lookup when the projection lags (read-your-writes), keeping the system correct even if the projector/CDC is down. List/search/uniqueness queries still require Mongo — the CQRS boundary holds for everything except the bounded point-read fallback.

---

## DD-05 — Eventuate CDC for outbox (not hand-rolled)

**Problem:** Publishing domain events to Kafka atomically with the DB write.

**Options:**
- A) Write to Kafka directly in the same transaction (impossible — different transactional resources)
- B) Hand-rolled outbox table + polling publisher
- C) Eventuate CDC (Postgres WAL-based)

**Choice:** C

**Rationale:** Hand-rolled outbox is correct in principle but adds a custom polling loop, offset management, and at-least-once delivery logic to maintain. Eventuate CDC provides all of this battle-tested. The application writes one Postgres transaction; CDC handles the rest. This is the single most important "I understand production microservices" signal in the codebase.

**Reaffirmed under review (don't swap, operate better):** the relay is *intrinsic* to the outbox pattern — CDC, Debezium, and a hand-rolled publisher are all the same mandatory component with the same operational profile (HA, lag, failure). Swapping one for another removes **zero** moving parts; it's lateral. So we keep Eventuate CDC and instead make its failure *boring*:
- **Durable outbox = no loss.** A down relay means latency, not lost events; it drains the `published=0` backlog on recovery.
- **HA via ZK leader election** so one instance down ≠ outage.
- **Lag alerting** on the age/count of unpublished rows — the real fix for the silent-failure mode (an idle CDC once left the read model empty with no error).
- **Downstream read-your-writes** (DD-15) so relay lag never reaches the user.

The "polling is a bottleneck" critique is rejected at this volume: ~50 TPS is a handful of rows per poll. The legitimate cost is *operational dependency*, addressed above — not throughput.

---

## DD-06 — Single deployable for Order Service (command + query in one process)

**Problem:** CQRS is a logical split; should it be a physical split (two deployables)?

**Options:**
- A) Two Spring Boot apps (command-service, query-service) sharing an event store
- B) One Spring Boot app, two internal packages (command/, query/)

**Choice:** B

**Rationale:** Independent scaling is not needed at portfolio scale. Two deployables add operational complexity (two image builds, two health checks, two config sets) without demonstrating a new pattern. The package boundary preserves the option to split later without rewriting. CQRS is about logical separation; the deployment boundary is an independent operational decision.

---

## DD-07 — Client-supplied Idempotency-Key

**Problem:** Network retries must not double-place orders.

**Options:**
- A) Gateway mints a key per request (server-generated)
- B) Client supplies a key per logical intent (client-generated)

**Choice:** B (same model as Stripe, Adyen)

**Rationale:** Server-generated keys deduplicate network-level retries only (same TCP connection). Client-generated keys deduplicate *intent-level* retries across connections, app restarts, and user double-taps. The client knows "this is the same button press" — the server does not. Scope: `(subscriberId, idempotencyKey)` to prevent cross-subscriber collision. TTL: 24 hours.

---

## DD-08 — Single global entitlement per subscriber-per-offer

**Problem:** Can a subscriber hold multiple concurrent entitlements for the same offer?

**Options:**
- A) Multiple concurrent (e.g., two Netflix bundles stacked)
- B) Single global (replace/renew semantics)

**Choice:** B

**Rationale:** Stacked entitlements create edge cases at the OTT provisioning boundary (which one is "active"?), at the cancellation boundary (which one gets refunded?), and at the renewal boundary. Single-global is the standard telco model for bundled services — "you either have Netflix or you don't." Simplifies the Saga, the OTT admin API, and the entitlement read model. Enforced at three layers: Order command validation, MongoDB unique partial index, OTT provisioning API `409`.

---

## DD-09 — OIDC (Authorization Code + PKCE) for OTT subscriber login

**Problem:** Subscriber authenticates to OTT using their telco identity.

**Options:**
- A) SAML
- B) OAuth2 only
- C) OIDC (OAuth2 + OpenID Connect)

**Choice:** C

**Rationale:** SAML is XML-heavy, browser-redirect-only, and designed for enterprise workforce IdPs — poor fit for a consumer mobile-first flow. OAuth2 alone is delegated *authorization*, not authentication — it doesn't tell the OTT who the subscriber is. OIDC adds an `id_token` (JWT with identity claims) on top of OAuth2's authorization flow. Spring Authorization Server provides the OP implementation — this is the correct production-grade choice; hand-rolling a JWT issuer is a security anti-pattern.

---

## DD-10 — Provisioning separate from OIDC login

**Problem:** OTT needs to know the subscriber has purchased access.

**Options:**
- A) Embed entitlement in the `id_token` claims
- B) OTT queries VA-BAGS for entitlement on every request
- C) Order Saga pushes entitlement to OTT via a dedicated admin API

**Choice:** C

**Rationale:** Option A: `id_token` is issued at login time, before or after purchase — embedding entitlement creates a stale-token problem. Option B: sync cross-service query = tight coupling, OTT cannot function if VA-BAGS is down. Option C: push at provisioning time. OTT owns a local entitlement table, is autonomous at access-check time, and the Saga step provides the natural trigger. The admin API is secured via client-credentials OAuth2 (machine-to-machine), which is a different security context from the subscriber's user token — keeping these separate is correct.

---

## DD-11 — Eventuate Tram for Inventory and Billing (not ES)

**Problem:** Saga participants need command handling and message infrastructure, but not event sourcing.

**Choice:** Eventuate Tram (participant mode) without ES.

**Rationale:** Inventory and Billing don't need the full audit trail / replay capability of ES. Using Tram-only for participants demonstrates that ES and Saga are independent patterns — each applied where they earn their complexity, not applied globally as a framework choice.

**Post-DD-14:** this is now the rule for *every* service, Order included — Tram everywhere, ES nowhere. The pattern this codebase demonstrates is "transactional outbox + domain events," with Saga layered on top.

---

## DD-12 — CDC Polling mode (not Postgres WAL)

**Problem:** Eventuate CDC can publish via two mechanisms: WAL tailing (`PostgresWal` profile, requires wal2json + logical replication slot + `wal_level=logical`) or table polling (`EventuatePolling` profile).

**Options:**
- A) `PostgresWal` — lower latency, but requires Postgres config changes (superuser ALTER SYSTEM, restart), wal2json extension, and a replication slot.
- B) `EventuatePolling` — CDC polls `eventuate.events` and `eventuate.message` tables for rows where `published=0`. Slightly higher latency.

**Choice:** B (`EventuatePolling`)

**Rationale:** Local dev on Windows + Postgres 18 makes WAL setup brittle (wal2json install on Windows is non-trivial; superuser `ALTER SYSTEM` requires restart and conflicts with shared Postgres instances). Polling mode works with any Postgres install out of the box. The latency difference (sub-100ms vs sub-10ms) is irrelevant at portfolio scale. In a production telecom system, WAL would be the right call; for this reconstruction it's a deliberate dev-experience trade-off, not a capability gap.

**Operational note — ZooKeeper resurfaces for CDC, not for Kafka:** Kafka itself runs in KRaft mode (no ZK quorum). The Eventuate CDC service, however, uses a ZooKeeper lock (`/eventuate/cdc/leader/tram`) for leader election across CDC replicas — this is a framework requirement even with a single CDC instance, and it is what makes a multi-instance HA deployment safe. The compose therefore ships `confluentinc/cp-zookeeper:7.7.1` solely as a CDC dependency.

**Single pipeline after DD-14:** with Event Sourcing removed there is no Local ES `eventuate.events` store, so the former `localpipeline` (outboxId=1) is retired. CDC now runs **one** `trampipeline` reader polling `eventuate.message` — which carries *both* the Order domain events (via `DomainEventPublisher`) and the Inventory/Billing saga command/reply traffic. One CDC service, one reader, one ZK lock, one Kafka publisher. *(`docker-compose.yml` still defines both pipelines until the code migration off Local ES lands.)*

---

## DD-13 — Apicurio for schema registry (not Confluent)

**Problem:** Event schemas need versioning and validation.

**Choice:** Apicurio (open source, same REST API as Confluent SR).

**Rationale:** Confluent Schema Registry requires a Confluent Platform license for production features. Apicurio is fully open source and exposes the same API, so the client configuration is identical. No vendor lock-in. In-memory mode for dev; persistent mode for staging/prod.

---

## DD-14 — Drop Event Sourcing on Order: state-stored aggregate + transactional outbox  *(supersedes DD-02)*

**Problem:** A design review of the stack — *CQRS + ES + Saga + CDC + Kafka + Mongo* — at the real volume (**~50 order TPS, ~300 read RPS**) raised three fair criticisms: (1) too many moving parts / fragile transaction story, (2) too many consistency layers, (3) CDC is an operational dependency. None of the patterns is needed for *scale* at this volume, so each must justify itself on capability.

**Options:**
- A) Keep full Event Sourcing on Order (status quo, DD-02).
- B) **State-store the Order aggregate (normal JPA row) and publish domain events via the Tram transactional outbox.** Events still reach Kafka; the aggregate is just not rebuilt from them.
- C) Drop events entirely, point-to-point calls.

**Choice:** **B**

**Rationale:** The key insight is that **"events on Kafka" and "event-sourcing the aggregate" are independent decisions.** Kafka-as-event-log was approved as a *strategic asset* (replay, analytics, recsys) — option B preserves that fully via the outbox. What it removes is ES's permanent tax: aggregate event-schema evolution + upcasting *forever*, snapshotting, and read-side eventual consistency on the write model. The audit/temporal requirements that originally justified ES (DD-02) are met more cheaply by the **Kafka event log + a small `order_status_history` table + the immutable `price_snapshot_id` on the order row**. At 50 TPS the rebuild-from-events capability has no operational payoff.

**What this changes / does not change:**
- **Removed:** `eventuate-local-java-spring-{jdbc,events}-starter`, the `eventuate.events` store, and the CDC `localpipeline`.
- **Added:** `eventuate-tram-spring-events` — `DomainEventPublisher` on the write side, `DomainEventHandlers` in the projector.
- **Unchanged:** Saga (DD-03), CDC + outbox (DD-05), Kafka, Mongo read model, idempotency. The Order write is still one Postgres transaction; events still flow to Kafka through the same relay.

**Net of the whole review:** *one thing to remove (ES), one thing to operate better (CDC — HA + lag alerts, DD-05), nothing to swap.*

---

## DD-15 — MongoDB read model is an optimization, with read-your-writes fallback

**Problem:** The read pipeline (Postgres → CDC → Kafka → projector → Mongo) is eventually consistent. A `POST` then immediate `GET` can `404` before the projection lands, and if CDC/projector is down the read model is silently empty — the "fragile" critique made concrete (and observed in practice once).

**Options:**
- A) Block the write until the projection is visible (synchronous read model) — defeats the point of CQRS/outbox.
- B) Serve all order reads from Postgres, drop Mongo — loses the per-screen read shapes and replay-rebuild.
- C) **Mongo is the primary read path; `GET /v1/orders/{id}` falls back to a bounded single-key Postgres lookup on a projection miss.**

**Choice:** **C**

**Rationale:** At 300 RPS Postgres can serve point reads trivially, so the read store is an *optimization*, not a correctness dependency. The fallback gives **read-your-writes** for a freshly placed order and keeps `GET`-by-id **correct even when CDC/projector is down** — directly answering criticisms (1) and (2). The fallback is deliberately narrow: only lookup-by-`orderId`. List, search, and entitlement-uniqueness queries still require the projection, so the CQRS boundary (invariant 3) holds everywhere except the one bounded point read.

**Related:** Catalog (read-heavy, weekly writes) gets a **Redis cache** — but invalidated by **local evict-on-write + a short TTL**, *not* by events (see **DD-17**; the writer and the cache are the same service, so there is nothing to publish to). Redis is *not* used for order read-your-writes either (the Postgres fallback is simpler and avoids a dual-write). Order *history/analytics* is served from the Kafka-fed warehouse, and the hot order tables are **time-partitioned** rather than moved to a separate operational DB.

---

## DD-16 — Catalog persisted in MongoDB (document model), not Postgres

**Problem:** The Catalog owns offer definitions, price snapshots, and eligibility rules across heterogeneous categories (DIGITAL / PHYSICAL / SLOT). The offer *shape* and its eligibility dimensions change often. Modelled relationally (the original `catalog.offers` table), this becomes a wide table of mostly-null columns — `max_device_age_months` only applies to PHYSICAL, etc. — and every new attribute or eligibility dimension is a DDL migration.

**Options:**
- A) Keep the relational `catalog.offers` table (JPA + Flyway on the shared Postgres).
- B) **Store offers as MongoDB documents** in a dedicated `vab_catalog` database.
- C) Keep relational but push variable attributes into a JSONB column (hybrid).

**Choice:** **B**

**Rationale:** The real driver is **shape change / polymorphism, not write throughput.** "Values change often" (prices, availability) is just write frequency, which Postgres handles fine at this volume and would *not* justify a switch on its own. What does justify it is that offers are polymorphic and their attribute/eligibility sets evolve continuously — a document model absorbs that without per-attribute migrations, and nested/arrayed eligibility rules read more naturally than flattened nullable columns. It is also a **net simplification**: Mongo is already in the stack for the order read model, so Catalog *drops* JPA + Flyway + the Postgres `catalog` schema rather than adding new tech. Postgres is now exclusively the Order write store.

**Guardrails kept honest:**
- **Price snapshots stay immutable and are referenced by id only.** Orders copy `priceSnapshotId` at placement, so losing the relational FK costs nothing — there is no live cross-store reference to maintain.
- Catalog is its **own** Mongo database (`vab_catalog`), separate from the order read model (`vab` / `orders_v1`), preserving the bounded-context boundary.
- Pairs with the Redis read-cache (DD-15) invalidated by `OfferPublished` / `PriceChanged`.

**What this changes:**
- **Removed:** `spring-boot-starter-data-jpa`, the Postgres driver, Flyway, and `V1__catalog_schema.sql` from `catalog-service`.
- **Added:** `spring-boot-starter-data-mongodb`; `Offer` is a `@Document`; `OfferRepository` is a `MongoRepository`; seed data moves from the Flyway script to `CatalogSeeder` (seeds on startup when the collection is empty). Eligibility logic is unchanged.

---

## DD-17 — Catalog read-cache: Redis with evict-on-write + TTL, not event-driven invalidation

**Problem:** Catalog is read-heavy and a hot path for every offer-browse, but it changes at most a couple of times a week. A Redis cache is an obvious win. DD-15 originally sketched the cache as *"invalidated by `OfferPublished`/`PriceChanged` events"* — but that framing conflated two independent concerns and, taken literally, was badly over-built for the actual requirement.

**The key observation:** event-driven cache invalidation exists to carry a "data changed" signal from *the service that owns the write* to *a different service that holds a cache*. Here they are **the same service** — catalog-service owns both the admin write API and the cached reads. There is no remote cache to notify, so there is nothing to publish an event *to*.

**Options:**
- A) **Event-driven** — author catalog domain events, publish them (Mongo outbox + change-stream relay, or Debezium + Outbox SMT), and evict on consume. Requires a Mongo replica set, a custom/operated relay, resume-token checkpointing, and (at >1 instance) leader election.
- B) **Evict-on-write + short TTL** — the admin write path evicts the (shared Redis) caches inline via `@CacheEvict`; a 15s TTL backstops out-of-band edits.
- C) TTL-only — simplest, but up to a full TTL of staleness even for an admin-API write.

**Choice:** **B**

**Rationale:** Because the cache is **shared Redis** and the writer is in-process, `@CacheEvict` on the admin write is immediately correct for **every** catalog instance — no events, no relay, no replica set, no leader election. The TTL (15s) is a *backstop*, not the mechanism: it bounds staleness from mutations that bypass the admin API (a manual `mongosh` edit, a re-seed). At ≈ twice-a-week writes, the sub-second freshness option A would buy is worth nothing, and option A's machinery (replica set + relay + checkpoint + HA) is a large, permanent operational cost for a pure caching need. Writes flush the whole (small) catalog cache (`allEntries = true`) rather than computing per-key evictions — simpler and cheaper at this write rate.

**This corrects, not contradicts, DD-15:** the cache is real; only its *invalidation mechanism* changed from "events" to "local evict + TTL." Catalog **domain events** (`OfferPublished` / `PriceChanged`) remain worthwhile, but for a *different* reason — a genuine cross-service consumer (e.g. the Order projector keeping price snapshots). That is a separate, deferred decision (and where the option-A publish mechanism would legitimately return), independent of caching.

**What this changes:**
- **Added:** `spring-boot-starter-data-redis` + `spring-boot-starter-cache`; `CacheConfig` (`@EnableCaching`, JSON value serialization, 15s TTL); `@Cacheable` on `OfferRepository.findByStatus`/`findById`; `OfferAdminService` (`@CacheEvict(allEntries)`) + `OfferAdminController` (`POST /v1/offers`, `PUT /v1/offers/{code}`, `POST /v1/offers/{code}:withdraw`); a `redis:7-alpine` service in `docker-compose.yml`.
- **Not added:** any Mongo replica set, outbox, change-stream relay, or HA leader election — none are needed for caching.

---

## DD-18 — Catalog cache is two-tier: Caffeine L1 (in-process) in front of Redis L2

**Problem:** With the Redis cache from DD-17, an offer-browse still pays a real cost. The catalog holds ~5000 offers, and the browse path materializes a large list on every request. Served from Redis, each request transfers and **JSON-deserializes ~5000 documents** — a per-request CPU + latency tax that a network cache hit does not remove. The cache avoids the *Mongo* round-trip but not the *serde* round-trip.

**The key observation:** the expensive part of a Redis hit here is not the network — it is reconstructing 5000 Java objects from JSON on every call. The fix is to keep the **already-materialized objects on the heap** of the reading instance, so a hot read does no deserialization at all.

**Options:**
- A) **Redis only (DD-17 status quo)** — one shared tier; every read deserializes.
- B) **Caffeine (in-process) only** — zero serde, but per-instance, cold on every restart/scale-out, and loses the shared L2 that backstops misses.
- C) **Two-tier: Caffeine L1 + Redis L2** — L1 serves hot reads with no serde; L2 absorbs L1 misses (cold start, post-eviction, TTL expiry) without going to Mongo.

**Choice:** **C**

**Rationale:** L1 removes the dominant cost (≈5000 deserializations/request) for the hot path — a hot read becomes a single in-process map lookup. L2 (Redis, DD-17) is retained as the shared far-cache that makes an L1 miss cheap and keeps cross-instance behavior sane. Both tiers use the **same short 15s TTL**, so the freshness contract is unchanged from DD-17; L1 is purely a latency/CPU accelerator, not a new source of truth. Reads flow **L1 → L2 → Mongo**; writes/evicts apply to **both** local tiers (plus the shared L2).

**Guardrails kept honest:**
- **Invalidation is unchanged in spirit (DD-17).** An admin write evicts this instance's L1 *and* the shared L2 inline (`@CacheEvict(allEntries)`). Other instances' L1 copies are signaled via a Redis pub/sub broadcast (**DD-19**) so they clear near-immediately; the ≤15s L1 TTL is retained only as a backstop for a missed broadcast. No domain events, no relay, no replica set, no leader election.
- **L1 is bounded** (`maximumSize`, `expireAfterWrite`) — a hot-set accelerator, not a system of record. A restart or scale-out simply starts with a cold L1 that fills from L2.
- **L1 holds live objects and serializes nothing;** only L2 uses the JSON serializer from DD-17.

**What this changes:**
- **Added:** `com.github.ben-manes.caffeine:caffeine`; a `TwoLevelCache` (`org.springframework.cache.Cache`) and `TwoLevelCacheManager` (`CacheManager`) in `catalog-service`; `CacheConfig` now defines the `cacheManager` pairing a Caffeine L1 (15s TTL, bounded size) with the DD-17 `RedisCacheManager` as L2.
- **Unchanged:** the cache names, the `@Cacheable`/`@CacheEvict` annotations, the TTL, and the Redis L2 serialization — DD-18 slots a near-cache in front of DD-17 without altering its contract.

---

## DD-19 — Cross-instance L1 invalidation via Redis pub/sub, with skip-self

**Problem:** DD-18's L1 is per-instance. When one catalog-service instance writes, it evicts its *own* L1 and the shared Redis L2 — but every *other* instance still holds stale objects in its L1 until that entry's 15s TTL expires. At ≈ twice-a-week writes a ≤15s cross-instance window is tolerable, but it is real, and the fix is cheap because Redis is already in the stack. (This is the "L1 broadcast invalidation" follow-up flagged in DD-18.)

**The key observation:** L2 already lives in Redis, which has pub/sub. An eviction can publish a tiny "I invalidated X" message that every instance subscribes to and applies to *its* L1 — no new infrastructure, no Mongo replica set, no relay. The originator must **skip its own message** (it already evicted L1 synchronously); a per-instance UUID in the payload makes that a one-line check and also prevents a self-reinforcing loop.

**Options:**
- A) **TTL only (DD-18 status quo)** — peers converge within 15s; no broadcast.
- B) **Redis keyspace notifications** — let Redis emit key-change events and have instances react. Works for *all* L2 changes (incl. out-of-band), but requires enabling `notify-keyspace-events`, is noisy (per-key, server-wide), and couples us to Redis server config.
- C) **App-level pub/sub broadcast on eviction, stamped with an instance id, skip-self.** The cache layer publishes `{senderId, cacheName, key|clearAll}`; peers clear their L1; the sender ignores its own.

**Choice:** **C**

**Rationale:** A write is now reflected on every instance within a network hop instead of a TTL. Putting the publish **in the cache layer** (`TwoLevelCache.evict/clear`) rather than in the admin service means *any* eviction broadcasts — not just the admin API — which is the stated requirement ("invalidation for reasons other than the admin API shall also clear all instances' L1"). Skip-self (compare `senderId` to the local `InstanceId`) avoids redundant work on the originator and, more importantly, stops the broadcast from looping: the broadcast handler clears L1 through **local-only** methods that do *not* re-publish. Option B was rejected as heavier and server-config-coupled for no practical gain here; option A leaves the known ≤15s gap.

**Guardrails kept honest:**
- **Pub/sub is fire-and-forget (at-most-once).** An instance that is down when a message is sent misses it — so the **15s L1 TTL is retained as the convergence backstop**. The broadcast is an *optimization* over the TTL, not a replacement; correctness never depends on delivery.
- **Publish failures never fail the write.** A Redis hiccup on `convertAndSend` is logged and swallowed; the triggering eviction (L1 + L2) has already happened and the TTL will converge peers.
- **No re-publish on receive.** Applying a peer's message uses `clearLocalL1()` / `evictLocalL1()`, which touch L1 only (L2 was already cleared by the originator) and do not broadcast.
- **Out-of-band L2 mutations** (a manual `redis-cli FLUSHDB`, a raw key delete) do not flow through the cache layer and so are *not* broadcast — they remain bounded by the 15s TTL, exactly as before. Catching those would need option B; it isn't worth it here.

**What this changes:**
- **Added (in `catalog-service`):** `InstanceId` (per-instance UUID bean); `CacheInvalidationMessage` (record `{senderId, cacheName, key}`); `CacheInvalidationPublisher` + `RedisCacheInvalidationPublisher` (publishes JSON over the `catalog:cache:invalidate` channel via `StringRedisTemplate`); `CacheInvalidationListener` (`MessageListener`, skip-self, local-only L1 clear); a `RedisMessageListenerContainer` bean subscribing to the channel. `TwoLevelCache` now publishes on every evict/clear and exposes non-publishing `clearLocalL1`/`evictLocalL1`; `TwoLevelCacheManager` threads the publisher into each cache.
- **Unchanged:** the TTLs, cache names, annotations, L2 serialization, and the DD-17 invalidation semantics — DD-19 only *widens* a local eviction into a fleet-wide one.

---

## DD-20 — Cache is fail-open: a Redis (L2) outage degrades to Mongo, never an error

**Problem:** MongoDB is the source of truth; Redis (L2) and Caffeine (L1) are accelerators (DD-17/DD-18). The catalog must therefore keep serving reads *and* accepting admin writes even if a cache backend is down. But Spring's default cache error handler (`SimpleCacheErrorHandler`) **rethrows** any exception the backend raises. With Redis unreachable, that turns a routine cache-aside read (L1 miss → `l2.get`) and a post-write eviction (`@CacheEvict` → `l2.clear`) into a `RedisConnectionFailureException` that propagates to the caller — so `GET /v1/offers` and the admin endpoints would **fail even though Mongo is healthy**. The accelerator becomes a single point of failure, which inverts its purpose.

**Which backend can actually fail:** Caffeine (L1) is in-process — it cannot be "down" independently; the only failure mode is JVM OOM, which crashes the service regardless of any handler. So the real exposure is **Redis (L2)**: network partition, restart, eviction-storm refusal.

**Options:**
- A) **Default handler (status quo)** — cache backend errors rethrow; a Redis outage fails reads and writes.
- B) **Custom `CacheErrorHandler` that logs + swallows** — cache calls that fail are treated as misses/no-ops, so the cached method falls through to Mongo.
- C) **Wrap every repository call in manual try/catch** — defeats the purpose of the declarative `@Cacheable`/`@CacheEvict` abstraction and scatters the policy.

**Choice:** **B**

**Rationale:** A near/far cache should be **fail-open**: if the cache can't help, get out of the way and let the source answer. `CacheErrorHandler` is exactly Spring's hook for this — registered once via `CachingConfigurer.errorHandler()`, it applies uniformly to every `@Cacheable`/`@CacheEvict` without touching repository code (rejecting C). Per operation:
- **get** → logged as a miss → Spring invokes the method → read served from **Mongo**.
- **put** → value just isn't cached this round → no correctness impact.
- **evict / clear** → the shared L2 couldn't be cleared, but the local L1 eviction in `TwoLevelCache` already ran and the **15s TTL converges** the rest; the write (already committed to Mongo) still returns success.

The trade-off is **bounded staleness, not lost data**: during a Redis outage a peer's L1 may serve ≤15s-old offers — acceptable for a catalog that changes ≈twice a week (DD-17).

**Guardrails kept honest:**
- **L1-first write ordering.** `TwoLevelCache.put` now warms **L1 before L2**, so a hot key stays served from the in-process cache even while Redis is down (only the one `l2.put` per key fails, is swallowed, and L1 still holds the value). Reads then cost at most one failed L2 probe per key per 15s TTL window.
- **Fast failure.** `spring.data.redis.timeout`/`connect-timeout` are bounded to **500ms** so a down Redis degrades to Mongo in ~½ second instead of stalling on Lettuce's multi-second default — the difference between "slightly slower" and "request times out."
- **Publish failures already swallowed (DD-19).** The pub/sub broadcast was already fail-soft; DD-20 extends the same fail-open posture to the *cache data path* itself.
- **Errors are visible.** Every swallowed failure is logged at WARN (`LoggingCacheErrorHandler`) so a Redis outage is observable, not silent.

**What this changes:**
- **Added (in `catalog-service`):** `LoggingCacheErrorHandler` (`CacheErrorHandler` — log + swallow for get/put/evict/clear); `CacheConfig` now implements `CachingConfigurer` and registers it via `errorHandler()`. `TwoLevelCache.put` reordered to L1-then-L2. `spring.data.redis.timeout`/`connect-timeout: 500ms` in `application.yml`.
- **Unchanged:** cache names, TTLs, annotations, L2 serialization, and the DD-17/18/19 invalidation semantics — DD-20 only changes *what happens when the cache backend errors*, not the happy path.
