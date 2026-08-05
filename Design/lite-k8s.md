# Lite — Containers & k8s (k3d) Runbook

Build the 4 Lite service images and deploy them to k3d, with **infra external** (on the
host via `docker-compose.lite.yml`) — the same split the full k8s set uses. Timezone is
**IST (Asia/Kolkata)** end-to-end. See also [lite-run.md](lite-run.md) (host-jar path),
[lite-cutlist.md](lite-cutlist.md).

## Topology

```
 host (Docker Desktop) ──────────────────────┐        k3d cluster "vabags"
   docker-compose.lite.yml                    │          namespace vabags-lite
     postgres  :5433→5432                      │            api-gateway  (LoadBalancer :8089)
     kafka     :9092 internal / :9094 host /   │◄── host.k3d.internal ── lite-order-service   :8081
               :9095 K8S (adv host.k3d.internal)│            lite-inventory-service :8082
     eventuate-cdc, zookeeper(cdc only)        │            lite-billing-service   :8083
                                               ┘
```

Pods reach host infra via `host.k3d.internal` (k3d injects it): Postgres `:5433`,
Kafka `:9095` (the additive K8S listener). Inter-service is Kafka, not HTTP — only the
gateway calls order over HTTP (in-cluster `lite-order-service:8081`).

## Files

- `Dockerfile.lite` — one image per module (`--build-arg MODULE=…`), IST baked in, non-root, layered.
- `buildLiteImages.ps1` / `importLiteImagesToK3d.ps1` — build the 4 images, import to k3d.
- `k8s-lite/00-namespace-and-config.yaml` — namespace `vabags-lite`, ConfigMap (endpoints, tz, heap), Secret (db creds).
- `k8s-lite/10-api-gateway.yaml` — LoadBalancer :8089, `lite` profile, `ORDER_SERVICE_URI`.
- `k8s-lite/11..13` — the 3 backends (ClusterIP).

## Steps

```bash
# 0) Build jars (host)
mvn -pl api-gateway,lite-order-service,lite-inventory-service,lite-billing-service -am -DskipTests package
```
```bash
# 1) Build the 4 images (IST baked)  +  import into k3d's containerd
pwsh ./buildLiteImages.ps1
pwsh ./importLiteImagesToK3d.ps1
```
```bash
# 2) Start external infra (now includes the K8S kafka listener on 9095)
docker compose -f docker-compose.lite.yml up -d
```
```bash
# 3) Deploy. Apply config first, then bring services up in WAVES (2c/4t box — avoid
#    starting 4 JVMs at once). Participants → orchestrator → gateway.
kubectl apply -f k8s-lite/00-namespace-and-config.yaml
kubectl apply -f k8s-lite/12-lite-inventory-service.yaml -f k8s-lite/13-lite-billing-service.yaml
kubectl -n vabags-lite rollout status deploy/lite-inventory-service deploy/lite-billing-service --timeout=360s
kubectl apply -f k8s-lite/11-lite-order-service.yaml
kubectl -n vabags-lite rollout status deploy/lite-order-service --timeout=360s
kubectl apply -f k8s-lite/10-api-gateway.yaml
kubectl -n vabags-lite rollout status deploy/api-gateway --timeout=360s
```

## Happy-path e2e against the cluster

The gateway is a LoadBalancer (k3d publishes on `localhost:8089`). The e2e's startup
health-check hits order directly, which is ClusterIP — so port-forward it:

```bash
kubectl -n vabags-lite port-forward svc/lite-order-service 8081:8081   # separate shell
# if the LoadBalancer isn't reachable on :8089, also: kubectl -n vabags-lite port-forward svc/api-gateway 8089:8089
```
```bash
mvn -pl lite-e2e-tests -Pe2e test -Dvab.gateway.url=http://localhost:8089 -Dvab.order.url=http://localhost:8081
```

## Teardown

```bash
kubectl delete namespace vabags-lite
docker compose -f docker-compose.lite.yml down     # add -v to wipe the pg volume
```

## Notes / gotchas
- **Timezone**: `Dockerfile.lite` sets OS `TZ=Asia/Kolkata` + installs tzdata, and defaults
  `JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Kolkata`. In k8s the ConfigMap's `JAVA_TOOL_OPTIONS`
  **overrides** that image ENV, so the tz flag is repeated there (alongside the heap flags).
  Without `-Duser.timezone` the JVM picks legacy `Asia/Calcutta`, which `postgres:18` rejects.
- **Gateway image name** `vabags/api-gateway:dev` is shared with the full build (same module).
  `buildLiteImages.ps1` (Dockerfile.lite) stamps the IST version; it runs the `lite` profile
  purely via the `SPRING_PROFILES_ACTIVE=lite` env in `10-api-gateway.yaml`.
- **Kafka K8S listener** (9095) is additive — host-jar runs still use EXTERNAL (9094).
- **Postgres port**: pods use `host.k3d.internal:5433` (compose maps host 5433 → container 5432).
- Requires the `04-tram-saga-schema.sql` init (see lite-run.md troubleshooting) — same host
  Postgres, so if the host-jar path already worked, the schema is in place.
