# Lite — Build & Run Runbook

How to build and happy-path-verify VA-BAGS Lite (see [lite-scope-outline.md](lite-scope-outline.md),
[lite-cutlist.md](lite-cutlist.md)). Lite = **api-gateway + lite-order + lite-inventory
+ lite-billing** over **Postgres + Kafka(KRaft) + eventuate-cdc**.

## Build

```bash
# Lite reactor is the default pom.xml. Build the 4 services (+ shared libs):
mvn -pl api-gateway,lite-order-service,lite-inventory-service,lite-billing-service -am package -DskipTests
```

The full 8-service build is preserved and still runnable via the backup aggregator:

```bash
mvn -f pom.xml.bkp -DskipTests package
```

## Run (waves, per the 2c/4t box)

> **Timezone (required on Windows/IST boxes):** the JVM default resolves to the
> legacy `Asia/Calcutta`, which pgjdbc sends to Postgres as `SET TimeZone`; the
> `postgres:18` container rejects it → Flyway fails with
> `invalid value for parameter "TimeZone": "Asia/Calcutta"`. Force the modern name:
> ```bash
> export JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Kolkata
> ```
> (Set once in each shell; carries into every `java`/`mvn` JVM below. Or pass
> `-Duser.timezone=Asia/Kolkata` per `java -jar`.)

> **Datasource port:** the compose maps host `5433 → container 5432`, so the
> services' `application.yml` datasource URLs use `localhost:5433`.

```bash
# 1) Infra (self-contained: Postgres + Kafka KRaft + ZK-for-cdc + eventuate-cdc)
docker compose -f docker-compose.lite.yml up -d
```

Wait until `docker compose -f docker-compose.lite.yml ps` shows kafka + eventuate-cdc healthy,
then start services (each connects to localhost:5433 + localhost:9094):

```bash
# 2) Participants first
java -Duser.timezone=Asia/Kolkata -jar lite-inventory-service/target/lite-inventory-service-0.1.0-SNAPSHOT.jar   # :8082
```
```bash
java -Duser.timezone=Asia/Kolkata -jar lite-billing-service/target/lite-billing-service-0.1.0-SNAPSHOT.jar       # :8083
```
```bash
# 3) Orchestrator
java -Duser.timezone=Asia/Kolkata -jar lite-order-service/target/lite-order-service-0.1.0-SNAPSHOT.jar           # :8081
```
```bash
# 4) Gateway — MUST use the lite profile (auth off, plain HTTP, order route only)
java -Duser.timezone=Asia/Kolkata -jar api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar --spring.profiles.active=lite   # :8089
```

## Happy-path e2e

```bash
mvn -pl lite-e2e-tests -Pe2e test -Dvab.gateway.url=http://localhost:8089
```

Drives: place PAY_NOW order (subscriber `sub-premium`, offer `OTT_NETFLIX_6M`) →
reserve → authorize → commit → CAPTURE(pivot) → confirm → fulfil(local stub) →
polls `GET /v1/orders/{id}` until **COMPLETED**.

Manual smoke (no Maven):

```bash
curl -i -X POST http://localhost:8089/v1/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 11111111-1111-4111-8111-111111111111' \
  -H 'X-Subscriber-Id: sub-premium' \
  -d '{"offerCode":"OTT_NETFLIX_6M","productType":"DIGITAL_SUBSCRIPTION","priceSnapshotId":"ps1","amount":999,"currency":"INR","billingMode":"PAY_NOW"}'
# then: curl http://localhost:8089/v1/orders/<orderId>
```

## Teardown

```bash
docker compose -f docker-compose.lite.yml down          # add -v to wipe the pg volume
```

---

## Troubleshooting

### `ERROR: relation "eventuate.saga_instance" does not exist` (order-service, on first saga)
The `eventuate` schema is created entirely by the DB-init SQL — nothing auto-runs the
eventuate flyway scripts, and each service's own Flyway only manages its own schema
(orders/inventory/billing). `01-eventuate-schema.sql` seeds the 7 ES + Tram messaging
tables but **not** the 4 saga tables; `04-tram-saga-schema.sql` adds them (DDL copied
from `eventuate-tram-sagas-spring-flyway` 0.26.0). A correct `eventuate` schema has 11
tables (7 + 4 saga).

Init scripts run only on a **fresh** volume. To fix a DB that came up before `04` existed:
```bash
# apply to the running container (keeps data)
docker exec -i vab-lite-postgres psql -U eventuate -d vab < deploy/postgres-init/04-tram-saga-schema.sql
```
or recreate from scratch:
```bash
docker compose -f docker-compose.lite.yml down -v && docker compose -f docker-compose.lite.yml up -d
# then restart the 4 services so their per-schema Flyway re-runs
```
Verify: `docker exec vab-lite-postgres psql -U eventuate -d vab -c "\dt eventuate.*"` → 11 rows.

---

## Decisions taken during implementation (deviations from the cut-list, for lower risk)

1. **Saga trim is minimal**: only the *fulfil participant* became an `invokeLocal`
   stub. The BILL_TO_MOBILE steps (checkLimit / allocate / append-ledger / reverse)
   were **kept** — they route to the retained inventory/billing, so no behaviour was
   deleted blind. Happy path uses PAY_NOW.
2. **lite-inventory / lite-billing** needed **no source changes** — only the pom
   artifactId. They are message-only participants (no mongo/redis/auth); their BTM
   handlers were left intact.
3. **Auth off**: the shared `api-gateway` uses profile gating (`SecurityConfig` →
   `@Profile("!lite")`, new `LiteSecurityConfig` permit-all under `lite`). The
   `lite-order-service` copy instead **drops Spring Security entirely** (identity via
   `X-Subscriber-Id` header, default `sub_demo`) — simplest for a dedicated module.
4. **ZooKeeper retained** in `docker-compose.lite.yml` **solely for eventuate-cdc
   leadership election**. Kafka itself is KRaft (ZK-free). Dropping ZK would risk CDC
   not polling → saga stalls; kept to stay safe for happy-path e2e.
5. **Postgres is now in-compose** (self-contained) rather than host-local, mounting
   only `deploy/postgres-init/01-eventuate-schema.sql`; service tables come from each
   service's Flyway on startup.

## Not yet verified (I could not compile/run per the no-build constraint)
- A clean `mvn package` of the lite reactor (watch for: unused-import warnings in
  `OrderCommandService` are harmless; a hard error would indicate a missed reference).
- CDC leadership + outbox relay end-to-end on the self-contained Postgres.
- `postgres:18` image tag availability in your registry (swap to your local PG major if needed).
