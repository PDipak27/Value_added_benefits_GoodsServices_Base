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

## 5. Run the walking skeleton (order + inventory)

In separate terminals:
```bash
# Terminal 1
cd order-service && mvn spring-boot:run

# Terminal 2
cd inventory-service && mvn spring-boot:run
```

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
    "priceSnapshotId": "ps_test_001",
    "amount":          599,
    "currency":        "INR",
    "billingMode":     "BILL_TO_MOBILE"
  }'
# → 202 Accepted, body: {"orderId":"ord_..."}

# Wait ~1s, then check status
curl -s http://localhost:8081/v1/orders/<orderId>
# → 200 OK, status: "CONFIRMED"
```

Replay the same request with the **same** `Idempotency-Key` → returns the **same** orderId, no new aggregate created.

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
docker exec vab-kafka kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic eventuate.entities --partitions 1 --replication-factor 1
docker exec vab-kafka kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic inventoryService --partitions 1 --replication-factor 1
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
