# VA-BAGS — Dev Setup

> **Working directory for all commands:** `source/vabags_base/` (Maven root, docker-compose root)

## Prerequisites

- Postgres 18 installed and running locally (port 5432)
- Docker Desktop (or Docker Engine on Linux)
- Java 17, Maven 3.9+

---

## 1. Create the Postgres database and user

```sql
-- run as superuser (psql -U postgres)
CREATE DATABASE vab;
CREATE USER eventuate WITH PASSWORD 'eventuate';
GRANT ALL PRIVILEGES ON DATABASE vab TO eventuate;
\c vab
GRANT CREATE ON SCHEMA public TO eventuate;
```

---

## 2. Apply the Eventuate schema

```bash
psql -U eventuate -d vab -f deploy/postgres-init/01-eventuate-schema.sql
```

### 2.1 Create the Keycloak database (§A-1 / DD-29)

```bash
# CREATE DATABASE can't run in a transaction block — run as superuser:
psql -U postgres -f deploy/postgres-init/03-keycloak-db.sql
```

---


## 3. Start infrastructure containers

```bash
docker-compose up -d
```

Services started:
| Container     | Host Port | Purpose                                        |
|---------------|-----------|------------------------------------------------|
| vab-kafka     | 9092      | Kafka broker (KRaft — no ZK quorum)            |
| vab-zk        | 2181      | ZooKeeper — CDC leader election only           |
| vab-cdc       | 8080      | Eventuate CDC (Polling → Kafka, 2 pipelines)   |
| vab-mongo     | 27017     | MongoDB read projections                       |
| vab-apicurio  | 8090      | Schema registry (dev; container :8080 → :8090) |
| vab-keycloak  | 8088      | OIDC Provider (Keycloak, Postgres-backed) — realm `vab` (§A-1) |

Check CDC is up:
```bash
curl http://localhost:8080/actuator/health
```

---

## 4. Build all services

```bash
mvn clean install -DskipTests
```

---

## 5. Run the services

Each service is an independent Spring Boot app. In separate terminals:

| # | Command | Port | Needed for |
|---|---------|------|-----------|
| 1 | `cd order-service && mvn spring-boot:run` | 8081 | always — command + saga + query |
| 2 | `cd inventory-service && mvn spring-boot:run` | 8082 | reserve/commit (PAY_NOW), allocate (BILL_TO_MOBILE), release; all types finite |
| 3 | `cd billing-service && mvn spring-boot:run` | 8083 | PAY_NOW authorize/capture (capture before fulfil, DD-24); BILL_TO_MOBILE checkAccountLimit/appendToLedger/reverseLedger |
| 4 | `cd fulfilment-service && mvn spring-boot:run` | 8086 | fulfil (per product type) |
| 5 | `cd notification-service && mvn spring-boot:run` | 8084 | reacts to `OrderConfirmed` / `OrderCompleted` / `OrderFailed` |
| 6 | `cd catalog-service && mvn spring-boot:run` | 8085 | offer browse + eligibility; order-service verifies `productType` against it |
| 7 | `cd api-gateway && mvn spring-boot:run` | 8080 | single front door that routes to the above |

**Minimal happy-path saga:** terminals 1–4 (order → inventory → billing → fulfilment).
Add 5 to see notifications fire; 6 to browse the catalog (and for `productType`
verification — order-service is fail-open if it's down); 7 to exercise routing.

> The gateway (8080) routes `/v1/offers/**` → catalog (8085) and
> `/v1/orders/**`, `/v1/entitlements/**` → order-service (8081). Inventory,
> billing and notification are internal (saga participants / event consumers)
> and are intentionally not routed.
>
> **Auth (§A-1, DD-29):** the OIDC Provider is **Keycloak** (`vab-keycloak`, :8088,
> realm `vab`), brought up by `docker-compose`. ott-service is now an OAuth2
> **resource server** — start **Keycloak before ott-service** (issuer discovery is
> eager). The fulfilment → OTT provisioning/revoke calls attach a Keycloak
> client-credentials Bearer (`vab-provisioning` / scope `ott:provision`)
> automatically.
>
> **Subscriber login (§A-2):** ott-service is *also* a server-side OIDC **login
> client** (public client `vab-ott`, Authorization Code + PKCE). Browse the catalog
> at `http://localhost:8087/v1/videos` — you'll be redirected to the Keycloak login.
> Sign in as the seeded demo user **`alice` / `alice`** (carries `subscriberId=sub-alice`,
> which owns `OTT_HOTSTAR_3M` via a seed row), then `GET /v1/videos/vid_hotstar_ipl/stream`
> returns `"Playing video: …"` while `…/vid_netflix_film/stream` returns `403`.
>
> **Edge auth (§A-3, DD-31):** the **gateway** (`:8089`) and **order-service** are now
> OAuth2 **resource servers** — start **Keycloak before both** (eager discovery). Call
> the subscriber surface **through the gateway** with `Authorization: Bearer <token>`;
> the gateway validates the JWT and relays it downstream. Catalog browse (`/v1/offers/**`)
> is public; orders/entitlements need a token (subject = the `subscriberId` claim, no
> body/param); back-office actions (`retry`/`complete`/`revoke-*`, `/v1/ops/**`) require
> the **`vab-admin`** realm role (seeded user **`vabadmin` / `vabadmin`**). Get a user
> token via the direct-access-grant on `vab-ott`:
> `curl -k -d grant_type=password -d client_id=vab-ott -d username=alice -d password=alice -d scope=openid \`
> `  https://localhost:8088/realms/vab/protocol/openid-connect/token`
> The `-Pe2e` suite mints such tokens itself (creating throwaway users via the Keycloak
> Admin API), so it now needs the **gateway running** too.
>
> **Edge TLS (§E2, prod-hardening):** Keycloak (`:8088`) and the gateway (`:8089`) serve
> **HTTPS** with a locally-trusted **mkcert** cert; the rest stay HTTP (edge-only TLS).
> **Generate the certs first — Keycloak and the gateway won't start without them:** see
> [`deploy/tls/README.md`](tls/README.md) (`mkcert -install` with `JAVA_HOME` set, then
> generate `keycloak.crt.pem`/`.key.pem` + `gateway-keystore.p12`). The issuer is
> `https://localhost:8088/realms/vab`. To run plain HTTP, set `GATEWAY_SSL_ENABLED=false`,
> point `KEYCLOAK_ISSUER`/`KEYCLOAK_TOKEN_URI` back to `http://…:8088`, and revert the
> compose Keycloak block.
>
> **Observability — logs (§C2, B-1):** every service now emits **structured JSON** on
> the console *and* **pushes logs to Loki** (loki4j), while still writing the
> human-readable `vabags-<svc>.log` files. `docker compose up -d` brings up **Loki**
> (`:3100`) and **Grafana** (`:3000`, anonymous admin — no login). Open
> `http://localhost:3000` → the **"VA-BAGS — Logs"** dashboard (or Explore →
> `{app="vabags"}`), filter by `service`/`level`, free-text search the message. If Loki
> is down, services still run (the appender buffers/drops — it never blocks). Override
> the push URL with `-DLOKI_URL=...`.
>
> **Observability — correlation (§C2, B-2):** every request gets an `X-Correlation-Id`
> at the gateway; it's carried through the whole saga over Kafka (Tram
> `MessageInterceptor`) and stamped on every log line. In the Grafana **"VA-BAGS — Logs"**
> dashboard, place an order, grab its `correlationId` from any log line, paste it into the
> **`$correlationId`** box, clear `$service` (All) → you see that one order's journey
> across gateway → order → inventory → billing → fulfilment → notification, plus the
> internal HTTP hops order→catalog and fulfilment→ott.
>
> **Observability — tracing (§C2, scope C):** services export OTLP spans (default
> `:4318`) to **Tempo**; the Tram/Kafka `traceparent` propagation is handled by Eventuate's
> `eventuate-tram-spring-micrometer-tracing-starter` (no custom span code). Sampling is
> `1.0`. `traceId` is on every log line. In Grafana, a Loki log line's **`traceId` is a
> link** → opens the **Tempo** span waterfall for that order across all services (the CDC
> relay shows as a time-gap — expected with the outbox); from a trace you can jump back to
> its logs. `docker compose up -d` now also starts **Tempo** (`:3200` query, `:4317` OTLP
> gRPC — the OpenTelemetry SDK default the services export to — and `:4318` OTLP HTTP).

---

## 6. Smoke test

```bash
# Place an order
curl -s -X POST http://localhost:8081/v1/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "subscriberId":    "sub_test_001",
    "offerCode":       "OTT_NETFLIX_6M",
    "productType":     "DIGITAL_SUBSCRIPTION",
    "priceSnapshotId": "ps_2026_05_netflix6m",
    "amount":          599,
    "currency":        "INR",
    "billingMode":     "BILL_TO_MOBILE"
  }'
# → 202 Accepted, body: {"orderId":"ord_..."}
# BILL_TO_MOBILE → checkAccountLimit → allocate inventory → appendToLedger → confirm;
# fulfil then provisions a DIGITAL_SUBSCRIPTION entitlement (externalRef) and the order COMPLETEs

# Wait ~1s, then check status
curl -s http://localhost:8081/v1/orders/<orderId>
# → 200 OK, status: "CONFIRMED" (then "COMPLETED" once fulfilled)
```

Replay the same request with the **same** `Idempotency-Key` → returns the **same** orderId, no new aggregate created.

### Per-product-type happy paths

Each type takes a different route through the saga and yields a different artifact
on the completed order (see `fulfilment` sub-doc in `GET /v1/orders/{id}`). The
examples below use `BILL_TO_MOBILE`, which allocates inventory in one step; a
`PAY_NOW` order instead reserves → authorizes → commits before confirming, then
**captures before fulfilling** (DD-24).

```bash
# PHYSICAL_GOOD — allocates stock, fulfil creates a shipment → trackingRef
curl -s -X POST http://localhost:8081/v1/orders -H "Content-Type: application/json" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"subscriberId":"sub_test_010","offerCode":"ACC_POWERBANK_20K","productType":"PHYSICAL_GOOD",
       "priceSnapshotId":"ps_2026_05_powerbank20k","amount":899,"currency":"INR","billingMode":"BILL_TO_MOBILE"}'

# SOFTWARE_LICENSE — allocates one activation key, fulfil echoes it → activationKey
curl -s -X POST http://localhost:8081/v1/orders -H "Content-Type: application/json" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"subscriberId":"sub_test_011","offerCode":"SW_ANTIVIRUS_1Y","productType":"SOFTWARE_LICENSE",
       "priceSnapshotId":"ps_2026_05_antivirus1y","amount":499,"currency":"INR","billingMode":"BILL_TO_MOBILE"}'
```

> **License key-pool exhaustion:** `SW_ANTIVIRUS_1Y` is seeded with only **3** keys.
> Place a 4th order for it (after 3 completions) → allocate/reserve fails with
> `POOL_EXHAUSTED` → order `FAILED`. Compensating an in-flight order returns its key
> to the FREE pool, so a subsequent order can succeed again.

### Compensation path (billing decline)

For `PAY_NOW`, billing declines any `amount > 999`. The authorize step runs *after*
inventory is reserved, so a decline exercises the saga's LIFO rollback (release the
reservation) and terminal failure:

```bash
curl -s -X POST http://localhost:8081/v1/orders \
  -H "Content-Type: application/json" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"subscriberId":"sub_test_002","offerCode":"OTT_NETFLIX_6M","productType":"DIGITAL_SUBSCRIPTION",
       "priceSnapshotId":"ps_test_001","amount":1500,"currency":"INR",
       "billingMode":"PAY_NOW"}'

# billing log → "Billing DECLINED ... exceeds limit 999"
# order-service log → authorize DECLINED → release reserved inventory → order FAILED
curl -s http://localhost:8081/v1/orders/<orderId>   # → status: FAILED
```

> For `BILL_TO_MOBILE`, the equivalent guard is `checkAccountLimit`: an amount above
> the subscriber's credit limit (default 1000), or a `SUSPENDED` account, fails the
> saga before inventory is allocated. Seeded accounts: `sub-suspended` (SUSPENDED),
> `sub-premium` (limit 5000).

### Capture hard-decline at the pivot (PAY_NOW, DD-26)

The **charge is the pivot** (`capture`), and the order is `CONFIRMED` right after it. A
capture **amount of `777`** hard-declines (demo trigger), which is the pivot *failing to
commit*: billing replies `withFailure`, the holds roll back LIFO, and the order ends
`FAILED`. Nothing was captured, so there is no refund (`CAPTURE_FAILED` is retired):

```bash
curl -s -X POST http://localhost:8081/v1/orders \
  -H "Content-Type: application/json" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"subscriberId":"sub_test_003","offerCode":"SW_ANTIVIRUS_1Y","productType":"SOFTWARE_LICENSE",
       "priceSnapshotId":"ps_test_001","amount":777,"currency":"INR",
       "billingMode":"PAY_NOW"}'

# billing log → "Billing CAPTURE DECLINED ... amount=777"
# order-service log → capture withFailure → compensate (release commit/reservation)
# notification log → subscriber SMS (cancelled, not charged)
curl -s http://localhost:8081/v1/orders/<orderId>   # → status: FAILED
```

> `amount=777` is below the `999` authorize ceiling, so authorize succeeds and the
> failure surfaces only at capture.

### Forward-recovery on a non-transient fulfil failure (DD-26)

Past the pivot the saga is **forward-only**. A non-transient delivery failure (route
closed, damaged good) replies `OrderFulfilmentFailed` as a **success-outcome** — the
saga does *not* roll back; it sets `forwardRecover` and moves forward:
refund (PAY_NOW) / reverseLedger (BTM) → release inventory → terminal
`CANCELLED_REFUNDED`. Demo trigger: an **offer code containing `FAIL`**:

```bash
curl -s -X POST http://localhost:8081/v1/orders \
  -H "Content-Type: application/json" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"subscriberId":"sub_test_003","offerCode":"OFF-FAIL","productType":"PHYSICAL_GOOD",
       "priceSnapshotId":"ps_test_001","amount":499,"currency":"INR",
       "billingMode":"PAY_NOW"}'

# fulfilment log → "Fulfil FAILED (DELIVERY_FAILED demo trigger)"
# order-service log → forwardRecover → refund → release → CANCELLED_REFUNDED
# notification log → subscriber SMS (cancelled and fully refunded)
curl -s http://localhost:8081/v1/orders/<orderId>   # → status: CANCELLED_REFUNDED
```

> A user cancel (`POST /v1/orders/<id>/cancel`) follows the same two checkpoints:
> pre-pivot → roll back to `CANCELLED` (not charged); pre-fulfil (post-pivot) →
> forward-recover to `CANCELLED_REFUNDED`. Once fulfilled/`COMPLETED`, cancel is `409`.

### Catalog & eligibility (catalog-service, :8085)

```bash
# Eligibility-filtered offer list for a subscriber profile (query params stand
# in for JWT claims until the gateway/OIDC stack exists).
curl -s "http://localhost:8085/v1/offers?planTier=PLUS&region=IN&kycLevel=MINIMAL"

# Offer detail (404 if withdrawn, e.g. OTT_LEGACY_3M)
curl -s http://localhost:8085/v1/offers/OTT_NETFLIX_6M

# "Why can't I buy this?" — rule-level eligibility result
curl -s -X POST http://localhost:8085/v1/offers/ACC_BUDS_PRO:evaluate \
  -H "Content-Type: application/json" \
  -d '{"planTier":"BASIC","region":"IN","deviceAgeMonths":36,"kycLevel":"MINIMAL"}'
# → eligible:false, failing rules: PLAN_TIER (needs PREMIUM), DEVICE_AGE, KYC
```

### Through the gateway (api-gateway, :8080)

With the gateway running, the same calls work via the single front door:

```bash
curl -s "http://localhost:8080/v1/offers?planTier=PLUS&region=IN"   # → catalog (8085)
curl -s  http://localhost:8080/v1/orders/<orderId>                   # → order-service (8081)
```

---

## TROUBLESHOOTING

### Read model empty / order stuck at PLACED / no projector logs

Symptom: order placement succeeds, `eventuate.events` and the saga tables update,
but MongoDB has no `vab` database and the order never reaches `CONFIRMED`.

Almost always means **CDC isn't running**. Check:
```bash
docker ps -a --filter name=vab-cdc          # look for "Created" or "Exited"
```
If `vab-cdc` is in `Created` (never started — its `service_healthy` gate on
kafka/zk wasn't met during a slow cold start) or `Exited`, start it:
```bash
docker start vab-cdc
docker logs -f vab-cdc                       # wait for "Started EventuateCdcServiceMain"
```
CDC then polls `eventuate.events`/`eventuate.message` for `published=0` rows and
flushes the backlog to Kafka; the projector consumes and the read model fills.

Quick confirmation the chain is live:
```bash
docker exec vab-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --list          # expect: orderServiceProjector
docker exec vab-mongo mongosh vab --quiet \
  --eval 'db.orders_v1.find().toArray()'            # expect: your order doc
```

### CDC fails to start citing ZooKeeper

The CDC `0.19.0.RELEASE` uses Kafka AdminClient for topic management, which
works with KRaft. If you see a ZooKeeper connection error, set this env var
in `docker-compose.yml` under `eventuate-cdc`:

```yaml
EVENTUATE_CDC_CREATE_TOPICS: "false"
```

Then pre-create required topics manually:
```bash
docker exec vab-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic eventuate.entities --partitions 1 --replication-factor 1
docker exec vab-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic inventoryService --partitions 1 --replication-factor 1
docker exec vab-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic billingService --partitions 1 --replication-factor 1
```

### MongoDB auth error

The `mongo:7` image has no auth by default in dev mode.  
If you see auth errors, check `MONGO_INITDB_ROOT_USERNAME` is not set.

### host.docker.internal not resolving (Linux)

The `extra_hosts: host.docker.internal:host-gateway` line handles this.
If it still fails, replace `host.docker.internal` in docker-compose with your
host machine's docker bridge IP (usually `172.17.0.1`).
```bash
ip addr show docker0 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1
```
