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

**Related:** Catalog (read-heavy, weekly writes) is a strong fit for a **Redis cache invalidated by `OfferPublished`/`PriceChanged` events** — but Redis is *not* used for order read-your-writes (the Postgres fallback is simpler and avoids a dual-write). Order *history/analytics* is served from the Kafka-fed warehouse, and the hot order tables are **time-partitioned** rather than moved to a separate operational DB.

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
