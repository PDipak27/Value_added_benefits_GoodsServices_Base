# Handoff — vbg_lite_Tests_fork (tests + app changes)

> Paste this as the first message of a new conversation. Scope: **improving unit / IT / e2e
> tests, and any application code changes** for VA-BAGS **Lite**. CI/CD pipeline work lives in
> the *other* fork (`vbg_lite_CICD_fork`) — don't do pipeline changes here.

## Where things are
- Repo: `D:\Dev\E_Learn\courses\DVA-C02\Projects\VA-BAGS\source\vabags_base` — branch **`cicdLite`**.
  GitHub: `PDipak27/Value_added_benefits_GoodsServices_Base`.
- Host: Windows 10, 2c/4t / 16GB. Docker Desktop. IST timezone matters (see gotchas).
- **Tip to avoid clashing with the CICD fork on git:** do test/app work on a branch off `cicdLite`
  (e.g. `lite-tests`) and merge back, since both forks touch the same repo.

## What "Lite" is
A trimmed edition of VA-BAGS for CI/CD + deployment practice: **api-gateway + lite-order-service +
lite-inventory-service + lite-billing-service** over **Postgres + Kafka (KRaft) + eventuate-cdc**.
Design docs: `Design/lite-scope-outline.md`, `Design/lite-cutlist.md`, `Design/lite-run.md`.

Lite modules are **copies** of the full modules (same `com.vab.*` packages), so changes here do NOT
affect the full `order-service`/`inventory-service`/`billing-service` (still on disk, built via
`mvn -f pom.xml.bkp`). `shared-events` + `shared-observability` are shared by both.

What was trimmed (relevant when writing/porting tests):
- **lite-order-service**: dropped the Mongo CQRS query side (`com/vab/order/query/**` except a new
  `query/api/LiteOrderQueryController` that reads the Postgres write model), `SecurityConfig` +
  Keycloak converter (auth off; identity via `X-Subscriber-Id` header), `command/fulfilment/**`,
  and the fulfilment saga participant (now an `invokeLocal` stub in `PlaceOrderSaga`). `CatalogClient`
  is a no-network stub that always returns `null`. `OrderCommandService` is the **6-arg** trimmed
  version (no FulfilmentReDrive / EntitlementRevoke / Mongo entitlement repo).
- **lite-inventory-service / lite-billing-service**: **source identical to full** (only pom
  artifactId changed) — BILL_TO_MOBILE handlers kept. So their full-version tests copy verbatim.

## Test layout + how to run
- **Unit tests** (`*Test`) → surefire, `test` phase. No Docker.
- **Integration tests** (`*IT`) → failsafe under **`-Pit`**, Testcontainers **Postgres 18 + Kafka**.
  Shared base `…/it/AbstractIntegrationTest.java` (static singleton containers, `withReuse(true)`,
  pre-seeds the eventuate schema from `deploy/postgres-init/01+04`). No eventuate-cdc, so ITs assert
  single-service behaviour (not end-to-end saga completion).
- **e2e** → module `lite-e2e-tests`, `-Pe2e`, drives a **running** stack via the gateway
  (`LiteHappyPathE2E`: place PAY_NOW order `sub-premium`/`OTT_NETFLIX_6M` → poll `GET /v1/orders/{id}`
  until COMPLETED). Needs infra + 4 services up (`docker-compose.lite.yml` + jars, gateway with
  `-Dspring.profiles.active=lite`).

```bash
mvn -Pit verify                                   # unit + IT + JaCoCo report
mvn verify                                         # unit-only (fast, no Docker)
mvn -pl lite-e2e-tests -Pe2e test -Dvab.gateway.url=http://localhost:8089
```

## Current test state (what exists)
- **Unit (11)**: order → `OrderTest`, `GlobalExceptionHandlerTest`, `OrderCommandServiceTest`
  (adapted to the 6-arg service); inventory → `InventoryCommandHandlersTest`,
  `InventoryReservationSweeperTest`, `InventoryItemTest`, `LicenseKeyTest`, `ReservationTest`;
  billing → `BillingCommandHandlersTest`, `BillingAccountTest`, `NextCycleLedgerEntryTest`.
- **IT (3)**: `OrderPlacementIT`, `InventorySeedIT`, `BillingSeedIT`.
- **e2e (1)**: `LiteHappyPathE2E`.
- **JaCoCo** wired in the parent `pom.xml` (`prepare-agent` + `report` at `verify` →
  `target/site/jacoco/jacoco.xml`; unit+IT merged via failsafe `@{argLine}`). Surefire excludes
  `**/Abstract*.java` so the Testcontainers IT base doesn't start Docker in the unit phase.
- **NOT yet verified with a real `mvn -Pit verify` run** — do that first in this fork.

## Full-version tests still available to mine (in `order-service/src/test/...`)
Skipped in the copy because they touch dropped Lite features — port only if you re-add the feature:
`query/api/*ControllerTest`, `query/projection/*ProjectorTest`, `config/KeycloakRealmRoleConverterTest`,
`OrderPersistenceIntegrationTest` (JPA slice). `api-gateway` has no unit tests in the full build.

## Gotchas
- **IST timezone**: without `-Duser.timezone=Asia/Kolkata` the JVM picks legacy `Asia/Calcutta`,
  which `postgres:18` rejects on `SET TimeZone` → Flyway fails. Pinned in the `it` profile's failsafe
  argLine and the Dockerfile.
- **eventuate schema**: created only by `deploy/postgres-init/01-eventuate-schema.sql` +
  `04-tram-saga-schema.sql` (11 tables total). Service Flyway only manages orders/inventory/billing.
- Some `*Test` files carry `withReuse(true)` and `System.out.println` debug the user added — fine.

## Likely next steps in this fork
- Run `mvn -Pit verify`, fix any red, confirm JaCoCo report generates.
- Raise coverage (saga branch/compensation paths, controller validation, error mapping).
- Add IT/e2e for failure paths (declined billing, out-of-stock inventory, cancel).
- Any app changes to make behaviour more testable (keep them in `lite-*` modules).
