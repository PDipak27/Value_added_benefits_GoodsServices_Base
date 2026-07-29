# Lite Cut-List — VA-BAGS Lite

Concrete per-module cut-list for the Lite edition scoped in
[lite-scope-outline.md](lite-scope-outline.md). **Planning only — no code here.**
Legend: **DELETE** (remove file/dir) · **STUB** (replace behaviour) · **KEEP** ·
**GATE** (move behind a Spring profile).

Approach confirmed: **3 new copied modules** (`lite-order-service`,
`lite-inventory-service`, `lite-billing-service`); originals stay on disk;
`api-gateway` keeps its name + a `lite` profile; `shared-events` /
`shared-observability` reused as-is.

---

## 0. Build layout

| Step | Action |
|---|---|
| Preserve full build | `git mv pom.xml pom.xml.bkp` (still buildable via `mvn -f pom.xml.bkp`) |
| New parent | Copy `pom.xml.bkp` → `pom.xml`; **keep GAV** `com.vab:va-bags:0.1.0-SNAPSHOT` (change `<name>` only), swap `<modules>` |
| Lite `<modules>` | `shared-events`, `shared-observability`, `api-gateway`, `lite-order-service`, `lite-inventory-service`, `lite-billing-service`, `lite-e2e-tests` |
| New module dirs | Copy `order-service`→`lite-order-service`, `inventory-service`→`lite-inventory-service`, `billing-service`→`lite-billing-service`; set each `<artifactId>` to `lite-*`; parent stays `com.vab:va-bags` |

> Same-GAV parent is required so the untouched originals (and `-f pom.xml.bkp`)
> still resolve their parent via the default `../pom.xml`.

---

## 1. lite-order-service

### 1a. CQRS read side (Mongo) → collapse to Postgres
| File / dir | Action |
|---|---|
| `com/vab/order/query/document/**` (OrderView, OrderSearchView, EntitlementView) | **DELETE** |
| `com/vab/order/query/repository/**` (mongo `*ViewRepository`) | **DELETE** |
| `com/vab/order/query/projection/**` (OrderProjector, OrderSearchProjector) | **DELETE** |
| `com/vab/order/query/config/**` (OrderSearchIndexConfig, EntitlementIndexConfig) | **DELETE** |
| `com/vab/order/query/api/OrderSearchController`, `OrderSearchService`, `EntitlementQueryController` | **DELETE** |
| `com/vab/order/query/api/OrderQueryController` | **STUB** → thin read backed by JPA `OrderRepository` (command side), returns order status by id |
| `application.yml` `spring.data.mongodb` block | **DELETE** |

### 1b. Catalog dependency → stub
| File | Action |
|---|---|
| `com/vab/order/command/catalog/CatalogClient` | **STUB** → in-memory offer table (code → productType/amount), or trust request payload; drop the WebClient call |
| `application.yml` `catalog:` block | **DELETE** |

### 1c. Fulfilment / OTT / entitlement → stub or drop
| File | Action |
|---|---|
| `com/vab/order/command/fulfilment/FulfilmentReDrive`, `EntitlementRevoke` | **DELETE** (admin ops for the dropped fulfilment/entitlement flows) |
| Saga fulfil step (see 1e) | **STUB** (`invokeLocal`) |

### 1d. Security → `lite` profile
| File | Action |
|---|---|
| `com/vab/order/config/SecurityConfig`, `KeycloakRealmRoleConverter` | **GATE** under `@Profile("!lite")`; add a `lite` `SecurityConfig` → `permitAll` |
| `application.yml` `spring.security.oauth2.resourceserver.issuer-uri` | **GATE** — must be absent under `lite` (issuer discovery is eager; would fail boot without Keycloak) |

### 1e. Saga trim — `PlaceOrderSaga` → PAY_NOW-only + fulfil stub
| Remove (BILL_TO_MOBILE) | Keep (PAY_NOW) |
|---|---|
| steps: checkAccountLimit, allocateInventory, appendToLedger, reverseLedger | reserve → authorize → commit → **capture (pivot)** → confirm → fulfil(stub) → finalize |
| endpoints: `checkAccountLimitEndpoint`, `allocateInventoryEndpoint`, `appendToLedgerEndpoint`, `reverseLedgerEndpoint` | `reserveInventoryEndpoint`, `authorizeBillingEndpoint`, `commitInventoryEndpoint`, `captureBillingEndpoint`, `releaseInventoryEndpoint`, `refundBillingEndpoint` |
| predicates: `isBillToMobile`, `shouldReverseLedger`; handlers for the removed replies | compensation (`release`) + forward-recovery (`refund`,`release`) stay — inert on happy path, kept for correctness |

- **Fulfil step (11):** replace `invokeParticipant(fulfilOrderEndpoint,…)` with
  `invokeLocal(this::fulfilStub)` — set a synthetic `fulfilmentRef`/pass through
  `activationKey`, no reply.
- **DELETE** `fulfilOrderEndpoint`, `FULFILMENT_CHANNEL`, and handlers
  `handleOrderFulfilled` / `handleOrderFulfilmentFailed` /
  `handleOrderProvisioningFailed` + the `provisioningFailed` finalize branch.
- `PlaceOrderSagaData`: drop BTM/provisioning-only fields (ledgerEntryId,
  provisioning flags) once their steps are gone.

### 1f. Keep
`command/api/OrderCommandController`, `command/service/OrderCommandService`,
`command/domain/**` (Order, OrderRepository, OrderStatus, PlaceOrderCommand),
`idempotency/**`, `api/GlobalExceptionHandler`, `OrderServiceApplication`.

---

## 2. lite-inventory-service
| File | Action |
|---|---|
| `command/InventoryCommandHandlers` | **KEEP** reserve / commit / release; **DELETE** the `allocate` handler (BTM-only) |
| `command/InventoryReservationSweeper` | **KEEP** (expiry release; Postgres) |
| `domain/**` (InventoryItem, Reservation, LicenseKey + repos) | **KEEP** |
| Security / auth | none present — nothing to gate (message-only participant) |
| `application.yml` | **KEEP** Postgres + eventuate; verify no Keycloak/Mongo keys |

---

## 3. lite-billing-service
| File | Action |
|---|---|
| `command/BillingCommandHandlers` | **KEEP** authorize / capture / refund; **DELETE** checkAccountLimit / appendToLedger / reverseLedger (BTM) |
| `domain/BillingAccount(+Repository)`, `BillingLedgerEntry(+Repository)` | **KEEP** |
| `domain/NextCycleLedgerEntry`, `NextCycleLedgerRepository` | **DELETE** (BTM next-cycle ledger) |
| Security / auth | none present |
| `application.yml` | **KEEP** Postgres + eventuate |

---

## 4. api-gateway (lite profile — not a new module)
| File / config | Action |
|---|---|
| `config/SecurityConfig`, `config/KeycloakRealmRoleConverter` | **GATE** `@Profile("!lite")`; `lite` → `permitAll`, no oauth2 resource server |
| `config/CorrelationGlobalFilter` | **KEEP** (correlation id / observability) |
| `application.yml` `oauth2` block | **GATE** — absent under `lite` |
| Routes | **DELETE** `catalog-service` route (+ any fulfilment/ott/notification routes); **KEEP/redefine** order command + query routes for lite |
| Redis rate-limiter | **VERIFY** `GatewayApplication` for a `RequestRateLimiter`/Redis bean → disable under `lite` (drop the dependency/filter) |

---

## 5. shared-events / shared-observability
- **REUSE as-is**, no `lite-` prefix, no edits.
- Unused BTM / fulfilment / ott / notification event classes stay compiled but
  unreferenced by Lite — harmless. Optional later cleanup, not required.

---

## 6. Infra & compose (new `docker-compose.lite.yml`)
| Component | Action |
|---|---|
| Postgres | **KEEP** — hosts order / inventory / billing schemas + eventuate CDC tables |
| Kafka | **KEEP**, **KRaft mode** (single node, no ZooKeeper) |
| eventuate-cdc | **KEEP** — points at Postgres + Kafka |
| ZooKeeper | **DELETE** (KRaft) |
| MongoDB | **DELETE** (read side collapsed) |
| Redis | **DELETE** (catalog cache + gateway limiter gone) |
| Keycloak | **DELETE** (auth off under `lite`) |
| Env removed from lite services | `KEYCLOAK_ISSUER`, `SPRING_DATA_MONGODB_URI`, redis, `catalog.*` |

---

## 7. lite-e2e-tests (happy-path only)
New small module (or trimmed copy of `e2e-tests`).
| Test | Action |
|---|---|
| `E2EBase` | **STUB** — drop Keycloak token acquisition; call gateway with no bearer |
| A single `LiteHappyPathE2E` | **KEEP/ADD** — place order (PAY_NOW) → poll status → `COMPLETED` |
| `GatewayAuthE2E`, `OttAuthE2E`, `OttE2E`, `OttVideoE2E`, `EntitlementsE2E`, `OrderQueryE2E` | **DELETE** (auth / OTT / entitlement / mongo-query — all out of Lite scope) |

---

## 8. Execution checklist (order matters)
- [ ] `git tag pre-lite-full` (snapshot) — optional but recommended.
- [ ] `git mv pom.xml pom.xml.bkp`; author lite `pom.xml` (same GAV, lite modules).
- [ ] Copy 3 modules → `lite-*`, set artifactIds.
- [ ] lite-order: delete query/mongo, stub catalog, stub fulfil, PAY_NOW saga, `lite` security.
- [ ] lite-inventory / lite-billing: strip BTM handlers + BTM domain.
- [ ] api-gateway: add `lite` profile (permitAll, lite routes, no redis).
- [ ] Author `docker-compose.lite.yml` (Postgres + Kafka-KRaft + cdc).
- [ ] Add `lite-e2e-tests` happy-path.
- [ ] Build: `mvn -pl <lite modules> -am package`; run e2e against gateway with `-Dspring.profiles.active=lite`.

---

## Open decisions
- [ ] `lite-e2e-tests` as a new module (recommended) vs. a `lite` test-profile
      inside existing `e2e-tests`.
- [ ] Trim unused event classes from `shared-events` now, or leave (recommended: leave).
