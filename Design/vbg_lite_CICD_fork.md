# Handoff — vbg_lite_CICD_fork (CI/CD pipeline work)

> Paste this as the first message of a new conversation. Scope: **CI/CD pipelines** (GitHub Actions,
> Jenkins, act, SonarQube, Docker Hub, ECR, EKS, k8s manifests) for VA-BAGS **Lite**. Test/app
> changes live in the *other* fork (`vbg_lite_Tests_fork`).

## Where things are
- Repo: `D:\Dev\E_Learn\courses\DVA-C02\Projects\VA-BAGS\source\vabags_base` — branch **`cicdLite`**.
  GitHub: `PDipak27/Value_added_benefits_GoodsServices_Base`. AWS region **ap-south-1**.
- Host: Windows 10, 2c/4t / 16GB, Docker Desktop, k3d, SonarQube local, Jenkins, `act`, `gh`.
- **Git push gotcha (Windows):** the Windows Credential Manager holds a stale token, so pushes need
  `git -c credential.helper= -c credential.helper="!gh auth git-credential" push origin cicdLite`.
  The PAT/token must have **`workflow`** scope (pushing `.github/workflows/*`). `gh auth login`
  (web browser) is the reliable way to refresh it. `cmdkey /delete:git:https://github.com` clears the
  stale one.

## What "Lite" is (deploy target)
**api-gateway + lite-order + lite-inventory + lite-billing** over **Postgres + Kafka (KRaft) +
eventuate-cdc**. Images named `vabags/<svc>`, built from **`Dockerfile.lite`** (IST timezone baked;
layered Spring Boot jar; non-root). Ports: gateway 8089, order 8081, inventory 8082, billing 8083.

## The pipelines (all built and working unless noted)
**GitHub Actions** (`.github/workflows/`):
- `lite-ci.yml` — GitHub-hosted CI: checkout → build + Testcontainers ITs (`-Pit verify`) →
  SonarQube (gated `ENABLE_SONAR`) → build 4 images → push **Docker Hub** (gated). JaCoCo report is
  produced by `verify`; Sonar auto-detects `target/site/jacoco/jacoco.xml`.
- `aws-gha-lite-ci.yml` — **manual** AWS CD: OIDC assume-role → build jars → **ECR** push → deploy
  `k8s-lite-aws` to **EKS** via kustomize → happy-path e2e. **This works** (imagePullBackOff was fixed
  by making ECR-native manifests + in-cluster infra; see below).
- `local-ci.yml` — runs via **nektos/act self-hosted** on the host (`.actrc`: `-P
  ubuntu-latest=-self-hosted --bind`, `REPO_DIR` + `defaults.run.working-directory`). Does k3d +
  compose + host Sonar + Docker Hub + e2e. Secrets via `.secrets` (gitignored; `.secrets.example`).

**Jenkins** (`jenkins/`):
- `Jenkinsfile.ci` — CI (build + IT + Sonar `withSonarQubeEnv('sonarqube')` + Docker Hub push via
  `dockerhub-creds`). `agent any` currently (was `label 'linux'`).
- `Jenkinsfile.aws` — CD (jars → ECR → EKS kustomize → e2e). **Auth now uses the EC2 instance-profile
  role (IMDS)** — the `withCredentials`/`aws-creds` wrappers were removed. Needs the role mapped into
  EKS RBAC (`eksctl create iamidentitymapping`).
- Guide: `Design/lite-jenkins-guide.md` (plugins, tools `jdk17`/`maven3`, credentials, §7 WSL2 agent).

## Kubernetes manifests
- `k8s-lite/` — **Docker Hub / k3d** set (`vabags/<svc>:dev`, `imagePullPolicy: IfNotPresent`,
  ConfigMap points infra at `host.k3d.internal`). Used by the k3d/local path.
- `k8s-lite-aws/` — **ECR / EKS** set (kustomize). Namespace `vabags-lite`. Includes **in-cluster
  infra** (Postgres+init, Kafka KRaft, ZooKeeper-for-cdc, eventuate-cdc) + the 4 services (bare image
  name `vabags/<svc>`, rewritten to ECR by `kustomize edit set image`). ConfigMap points at in-cluster
  DNS (`postgres:5432`, `kafka:9092`). `configMapGenerator` builds the pg-init ConfigMap from
  `../deploy/postgres-init` → **must build with**
  `kubectl kustomize --load-restrictor=LoadRestrictionsNone k8s-lite-aws | kubectl apply -f -`.
- Deep-dive: `Design/lite-aws-eks-guide.md` (OIDC, the two CloudFormation stacks eksctl creates,
  VPC/subnets/NAT/route-tables/SGs/IAM, cluster mode, autoscaling, cost/teardown).

## Key decisions / gotchas
- **Jenkins on EC2 is the chosen home** (t3.medium 4GB floor; micro/small OOM on Testcontainers +
  image builds). WSL2 agent worked but bled Windows configs onto Linux (`git.exe` "dubious
  ownership", global Shell executable `C:\...\bash.exe` breaking `sh`). EC2 = native Linux, none of
  that. On EC2 use the **instance-profile role** (no stored keys) — but map it into EKS RBAC.
- **EKS cost discipline** (~$20 budget): control plane $0.10/h even idle → `eksctl delete cluster`
  nightly; **no NAT gateway** (public-subnet nodes, `--vpc-nat-mode Disable`); spot nodes; billing
  alarm. Node role needs `AmazonEC2ContainerRegistryReadOnly` to pull ECR.
- **IST timezone**: `-Duser.timezone=Asia/Kolkata` everywhere (postgres:18 rejects Asia/Calcutta).
- Registries: **Docker Hub** for CI, **ECR** for AWS CD. Both under `vabags/<svc>`.

## Uncommitted / recent (verify with `git status`)
Recent local work not necessarily pushed: `Jenkinsfile.aws` instance-role edit, unit tests + JaCoCo in
the parent `pom.xml` (from the tests fork's session), `k8s-lite-aws/`. Coordinate commits with the
tests fork (same branch). Suggested: keep CICD work on `cicdLite`; ask the tests fork to use a side
branch.

## Likely next steps in this fork
- Stand up **Jenkins on EC2** (t3.medium), instance role + EKS RBAC mapping, run `Jenkinsfile.ci`
  then `Jenkinsfile.aws`.
- SonarQube **quality gate** (`waitForQualityGate` + webhook); confirm JaCoCo coverage shows in Sonar.
- Harden: approvals before EKS deploy, Jenkins shared library for the build/push loop, ECR lifecycle
  policy, teardown automation.
- Optional: ECS (Fargate) variant as a contrast to EKS.
