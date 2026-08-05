# Lite — Local CI/CD via nektos/act

`.github/workflows/local-ci.yml` runs the whole Lite pipeline on your PC through
[act](https://github.com/nektos/act):

```
SonarQube → build + unit + integration tests (Testcontainers)
          → build 4 images → push to Docker Hub
          → infra up (docker-compose.lite.yml) → deploy 4 svcs to k3d → happy-path e2e
```

This is the **local** counterpart to `lite-ci.yml` (the GitHub-hosted one that pushes to
ECR). Two separate files on purpose: this one assumes host tools + localhost services.

## Why act self-hosted mode
k3d, docker-compose, the host SonarQube (`localhost:9000`) and Testcontainers must all
share **one Docker daemon** and reach each other over `localhost`. Running the job inside
an act container breaks that, so `.actrc` pins `-P ubuntu-latest=-self-hosted` — every
step runs directly on the host (Git Bash), exactly like a self-hosted runner.

## Prerequisites (host)
- JDK 17, Maven, Docker Desktop, `k3d` + `kubectl`, `act` — all on `PATH`.
- **SonarQube server running**: `C:\SonarQube\26-7\bin\windows-x86-64\StartSonar.bat`
  → wait for `http://localhost:9000` (first run: log in admin/admin, set a password).
- A Docker Hub account + a Personal Access Token.
- (Optional) an existing k3d cluster named `vabags`; the workflow creates one with an
  `8089:8089@loadbalancer` mapping if missing.

## One-time setup
```bash
cp .secrets.example .secrets    # then edit .secrets with real tokens (it's gitignored)
```
`.secrets` holds `SONAR_TOKEN`, `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`.
The Docker Hub push namespace defaults to `DOCKERHUB_USERNAME`.

## Run the full pipeline
```bash
act workflow_dispatch -W .github/workflows/local-ci.yml --secret-file .secrets
```
(`.actrc` supplies `-P ubuntu-latest=-self-hosted --bind`.) The gateway is port-forwarded
to `localhost:8089` and the e2e drives place → **COMPLETED**. Cluster + infra are left up
for inspection; port-forwards are killed on exit.

## Run just the integration tests (no act)
```bash
mvn -Pit -pl lite-order-service,lite-inventory-service,lite-billing-service verify
```
- `OrderPlacementIT` — POST /v1/orders → 202, order row PLACED, GET returns it (web + JPA + Tram outbox on real PG + Kafka).
- `InventorySeedIT` / `BillingSeedIT` — participants boot on real PG + Kafka; Flyway seed present.
- Testcontainers: Postgres 18 (pre-seeded with `deploy/postgres-init/01` + `04`, as the compose does) + Kafka. No CDC, so ITs assert single-service behaviour, not end-to-end completion.

## Caveats / gotchas
- **Timezone**: the `it` profile pins `-Duser.timezone=Asia/Kolkata` on the failsafe JVM
  (else pgjdbc sends `Asia/Calcutta`, which `postgres:18` rejects).
- **SonarQube must be up first** — `sonar:sonar` fails fast if `localhost:9000` is down.
  No JaCoCo yet, so coverage shows 0% (add `jacoco-maven-plugin` later if you want it).
- **`act` on Windows** runs `run:` steps in Git Bash; `docker`/`mvn`/`k3d`/`kubectl` must
  resolve there. Background port-forwards + `sleep` rely on that shell.
- **Ports**: infra maps Postgres `5433→5432`, Kafka `9094`(host)/`9095`(k3d). The gateway
  LoadBalancer publishes `8089`; the e2e still port-forwards for reliability.
- If a step fails mid-run, infra/cluster stay up — re-run is idempotent (compose `--wait`,
  `kubectl apply`, image re-import all converge).
