# VA-BAGS — Deployment & CI/CD Interview-Prep Strategy

**Goal:** Study deployment + CI/CD hands-on to prepare for Senior Backend Engineer (10 YOE) interviews, targeting enterprise services companies in India.
**Timeline:** 7 days × 7 hrs = 49 hrs. **AWS budget:** ≤ $20 (ap-south-1 / Mumbai). **Scope:** happy-path e2e only.
**Certs already held:** AWS SAA-C03, DVA-C02.

---

## Key reframe

Stop fighting local hardware (2c/4t, 16GB). ~15 containers (7 infra + 8 JVMs ≈ 10–12GB) will never fit locally, no matter the tuning. Rent CPU by the hour instead — it's cheaper than the time lost *and* more interview-relevant.

---

## Verdict on the 4 options

| Option | Take | Verdict |
|---|---|---|
| **1. Docker Desktop k8s (+AWS)** | Heavier than k3d, same single-node RAM ceiling. Solves nothing k3d didn't. | **Skip** |
| **2. k3d on one EC2** | Your exact k3d setup on rented CPU, zero hardware fight. Cheapest real k8s. | **Primary (daily playground)** |
| **3. EKS** | Highest interview signal for senior. Managed control plane, IRSA, ALB controller. Budget-sensitive. | **Showcase — 1.5 days, tear down nightly** |
| **4. ECS (Fargate)** | AWS-native, no control-plane fee, common in Indian AWS shops. The "why k8s vs ECS" contrast. | **Contrast — 0.5–1 day** |

Doing **2 + 3 + 4** covers the three orchestration answers interviewers probe: self-managed k8s, managed k8s, and non-k8s.

---

## Service trimming

Number of services is noise; the patterns are the signal. 8 JVMs just burn RAM/money. Keep the distributed-systems story (saga/CQRS/outbox) — it's the crown jewel.

**Rule:** keep `api-gateway` + the **saga orchestrator** + its **2 participants** ≈ 4 services, plus only the infra those need.

- **Keep:** Postgres (per-service DBs), Kafka, `eventuate-cdc` (the outbox/CDC pattern is the best interview story).
- **Drop `zookeeper`:** run **Kafka in KRaft mode** → one fewer container + a modern talking point. (Verify Eventuate CDC's Kafka compatibility first.)
- **Drop for cloud iterations:** `keycloak`, `redis`, `mongo` unless a kept service hard-depends on them. Stub auth for happy-path e2e.
- Keep all 8 + full infra **only in docker-compose** as the "it all works" reference; deploy the trimmed ~4 everywhere else.

---

## 7-day plan (hands-on → interview questions)

| Day | Hands-on | Cost | Arms you for |
|---|---|---|---|
| **1** | Multi-stage + **layered** Dockerfiles (upgrade copy-prebuilt-jar), `.dockerignore`, image-size before/after. Trim to ~4 svcs, KRaft Kafka, happy-path e2e on compose. | $0 | Containerize Spring Boot / image optimization / layer caching |
| **2** | **CI/CD:** GitHub Actions build→test→image→push **ECR**. Then Jenkins in a container, port one job to a `Jenkinsfile`. | ~$1 | Pipeline walkthrough / GHA vs Jenkins / secrets in CI |
| **3** | **Option 2:** t3.xlarge spot → k3d → pull from ECR. Full manifests: Deployment/Service/ConfigMap/Secret, probes, resource req/limits, rolling update, **HPA**. Wave-startup, e2e via port-forward. | ~$3 | k8s objects / probes / config & secrets / zero-downtime & rollback / **JVM tuning in constrained containers** |
| **4** | **Helm** chart for the ~4 svcs (values per env) + **Ingress** + wire existing **Grafana/Loki/OTel**. | ~$2 | Helm templating / ingress / observability in prod |
| **5** | **Option 3 (EKS):** `eksctl`/Terraform cluster, ALB ingress controller, deploy Helm chart, e2e against ALB. **`eksctl delete cluster` at day end.** | ~$8 | Managed k8s / EKS internals / IRSA / node groups vs Fargate |
| **6** | **Option 4 (ECS Fargate):** task defs + service + ALB via Terraform, e2e. **Delete at day end.** | ~$4 | ECS vs EKS trade-offs / Fargate / task definitions |
| **7** | Architecture + deployment diagrams, README, cost retro, rehearse Q&A with artifacts, verify teardown + billing. | $0 | Synthesis / "tell me about a system you shipped" |

---

## Cost estimate (ap-south-1, disciplined)

| Item | Est. |
|---|---|
| Day 3–4 t3.xlarge **spot** (~14 hr) | ~$1–3 |
| Day 5 EKS: control plane (~7hr) + 2× t3.large spot + ALB | ~$6–9 |
| Day 6 ECS Fargate (4 tasks, ~7hr) + ALB | ~$3–5 |
| ECR + EBS + misc | ~$2 |
| **Total** | **~$14–19** |

**Three budget killers — guard against all:**
1. **NAT Gateway** (~$1+/day idle) → use **public subnets** for nodes, no NAT.
2. **Idle EKS control plane** ($0.10/hr even at 0 nodes) → create only on Day 5, `delete cluster` same day.
3. **Forgotten EC2 / leaked ALBs** → CloudWatch **billing alarm at $10 and $18**; teardown checklist every day.

Use **spot** everywhere → comfortably under $20. If near budget on Day 6, shorten ECS to a 2-hr "prove it deploys" rather than skipping.

---

## Interview coverage map

- **Containerization:** multi-stage/layered images, `.dockerignore`, base-image choice → Day 1
- **CI/CD:** stages, artifacts, ECR, CI secrets, GHA vs Jenkins → Day 2
- **Kubernetes:** objects, probes, config/secrets, rollout/rollback, HPA, ingress, Helm → Days 3–4
- **Managed cloud:** EKS (IRSA, ALB controller, node groups/Fargate), ECS/Fargate → Days 5–6
- **IaC:** eksctl/Terraform, Helm → Days 4–6
- **Observability:** Grafana/Loki/OTel already wired — a real differentiator → Day 4
- **War story:** JVM-in-container tuning (Xmx, SerialGC, TieredStopAtLevel, HikariCP caps) under CPU/RAM pressure → Day 3
