# Lite — Jenkins CI/CD Guide

The Jenkins equivalent of the GitHub Actions pipelines, reusing the same build, images, kustomize
overlay (`k8s-lite-aws/`) and e2e. Two declarative pipelines mirror the two GHA workflows:

| Concern | GitHub Actions | Jenkins |
|---|---|---|
| CI: build + IT + Sonar + Docker Hub | `.github/workflows/lite-ci.yml` | `jenkins/Jenkinsfile.ci` |
| CD: ECR + EKS deploy + e2e | `.github/workflows/aws-gha-lite-ci.yml` | `jenkins/Jenkinsfile.aws` |
| Local (act) | `.github/workflows/local-ci.yml` | run either Jenkinsfile on a local agent |

Nothing about the app, images, or k8s manifests changes — only the *orchestrator*.

---

## 1. GHA → Jenkins concept mapping

| GitHub Actions | Jenkins equivalent |
|---|---|
| `runs-on: ubuntu-latest` (ephemeral VM) | an **agent** (persistent node/pod); tools + Docker must be present |
| `uses: actions/checkout` | `checkout scm` (implicit for "Pipeline from SCM") |
| `uses: actions/setup-java` | `tools { jdk 'jdk17'; maven 'maven3' }` (Global Tool Config) |
| `uses: aws-actions/configure-aws-credentials` (**OIDC**) | `withCredentials([... AmazonWebServicesCredentialsBinding ...])` (**stored key**) — or instance role / IRSA (§5) |
| `uses: aws-actions/amazon-ecr-login` | `aws ecr get-login-password | docker login` |
| SonarCloud via `sonar.host.url`/token | `withSonarQubeEnv('sonarqube')` (SonarQube Scanner plugin injects URL+token) |
| repo **secrets** | Jenkins **Credentials** |
| repo **vars** / `workflow_dispatch` inputs | pipeline **parameters** |
| `if:` on steps | `when { expression { ... } }` on stages |
| job runner has Docker built-in | agent must expose a **Docker daemon** (socket) itself |

The single biggest difference is **auth to AWS** — see §5.

---

## 2. Jenkins prerequisites

**Plugins** (Manage Jenkins → Plugins):
- Pipeline, Pipeline: SCM Step, Git
- Docker Pipeline (for image work)
- SonarQube Scanner
- Pipeline: AWS Steps / CloudBees AWS Credentials (`AmazonWebServicesCredentialsBinding`)
- JUnit, Workspace Cleanup (`cleanWs`)

**Global Tools** (Manage Jenkins → Tools) — names must match the `tools {}` block:
- JDK named **`jdk17`**
- Maven named **`maven3`**

**Agent toolchain** — the node the pipeline runs on must have on `PATH`:
- `docker` (with access to a running daemon — for Testcontainers *and* image builds)
- `aws` CLI, `kubectl`, `kustomize`  (for the AWS pipeline)
- `git`
- A **bash** shell — the pipelines use `sh` steps (see §6 for Windows).

**SonarQube server** (Manage Jenkins → System → SonarQube servers): add one named **`sonarqube`**
with its URL + an auth token credential. `withSonarQubeEnv('sonarqube')` then supplies both to Maven.

---

## 3. Credentials to create (Manage Jenkins → Credentials)

| ID | Kind | Used by | Notes |
|---|---|---|---|
| `dockerhub-creds` | Username with password | CI | Username doubles as the image namespace (`$DOCKERHUB_USR`) |
| `aws-creds` | AWS Credentials (access key + secret) | AWS CD | The IAM user/role needs ECR push + `eks:DescribeCluster`, and must be mapped into the cluster's RBAC (`eksctl create iamidentitymapping`) — same as the GHA role |
| (SonarQube token) | Secret text, attached to the SonarQube server config | CI | Not referenced directly in the Jenkinsfile |

---

## 4. Create the two jobs

For each: **New Item → Pipeline** → *Pipeline* section → **Pipeline script from SCM** → Git (this
repo, branch `cicdLite`) → **Script Path**:
- CI job → `jenkins/Jenkinsfile.ci`
- AWS job → `jenkins/Jenkinsfile.aws`

Both expose **parameters** (CI: `RUN_SONAR`, `PUSH_IMAGES`; AWS: `DEPLOY`, `RUN_E2E`, `AWS_REGION`,
`EKS_CLUSTER_NAME`) via "Build with Parameters". A Multibranch Pipeline works too (point it at
`jenkins/Jenkinsfile.ci`).

Typical flow: run **CI** on every push; run **AWS** manually (it costs money and needs the cluster),
exactly like the GHA split.

---

## 5. AWS auth — the important difference

GitHub Actions authenticates to AWS with **OIDC** (a short-lived token, no stored keys). Jenkins is
**not** an OIDC provider GitHub-style, so these pipelines use a stored **`aws-creds`** access key via
`AmazonWebServicesCredentialsBinding`. That's fine for a study/local Jenkins, but for real use prefer
one of these (no long-lived keys):

- **Jenkins on EC2** → attach an **IAM instance role** with ECR/EKS perms; drop the `withCredentials`
  block entirely (the SDK/CLI picks up the instance role automatically).
- **Jenkins on EKS** → **IRSA**: annotate the Jenkins agent's ServiceAccount with an IAM role; again
  no stored keys.
- **OIDC to AWS** is possible via the *aws-actions*-style web-identity flow but needs extra plugin
  wiring — usually not worth it over an instance role.

Either way, the IAM identity still needs the cluster **RBAC mapping** (`eksctl create
iamidentitymapping --arn <role> --group system:masters`) — getting a kubeconfig is not cluster access.

---

## 6. Stage-by-stage (what each does)

**`Jenkinsfile.ci`**
1. **Checkout** — `checkout scm`.
2. **Build + integration tests** — `mvn -Pit … verify`; Testcontainers starts real Postgres + Kafka
   (agent Docker required). Results published via `junit` (surefire + failsafe reports).
3. **SonarQube** (`when RUN_SONAR`) — `withSonarQubeEnv` + `mvn sonar:sonar`.
4. **Build images** — 4 images from `Dockerfile.lite`, tagged `$DOCKERHUB_USR/<svc>:<sha>` + `:latest`.
5. **Push Docker Hub** (`when PUSH_IMAGES`) — `docker login` via the credential, push both tags.
- `post`: `docker logout` + `cleanWs`.

**`Jenkinsfile.aws`**
1. **Checkout**.
2. **Build jars** — `-DskipTests` (ITs already ran in CI).
3. **Build + push images to ECR** — derive the registry from `aws sts get-caller-identity`, ECR login,
   create repos if missing, build + push `<registry>/vabags/<svc>:<sha>`.
4. **Deploy to EKS** (`when DEPLOY`) — `update-kubeconfig`, `kustomize edit set image` to the ECR tag,
   `kubectl kustomize --load-restrictor=LoadRestrictionsNone k8s-lite-aws | kubectl apply -f -`, then
   wave `rollout status` (infra → participants → orchestrator → gateway).
5. **Happy-path e2e** (`when DEPLOY && RUN_E2E`) — port-forward gateway + order, run `-Pe2e`.
- `post`: kill port-forwards + `docker logout` + `cleanWs`.

> The `--load-restrictor=LoadRestrictionsNone` flag is required because `k8s-lite-aws/kustomization.yaml`
> generates the Postgres init ConfigMap from `../deploy/postgres-init` (above the kustomize root).

---

## 7. Windows: run the build on a WSL2 Linux agent (required)

The pipelines use `sh` (bash) with loops and pipes. On the **Windows built-in node** the `sh` step
does not work reliably — pointing it at Git Bash still fails with
`sh: line 1: C:\Program Files\Git\bin\bash.exe: command not found` (the durable-task launcher +
spaces in the path). Don't fight it: run the build on a **WSL2 Ubuntu agent**, where `sh`, Docker and
Testcontainers all work natively. No pipeline changes beyond pinning the agent label.

**Step 1 — prep WSL2 Ubuntu** (Docker Desktop → Settings → Resources → **WSL integration** → enable
for the distro, so `docker` works inside WSL):
```bash
sudo apt update && sudo apt install -y openjdk-17-jdk git curl
docker version   # must succeed inside WSL (proves Docker Desktop integration)
```

**Step 2 — create the Jenkins node**: Manage Jenkins → **Nodes → New Node**
- Name `wsl-linux`, type *Permanent Agent*
- Remote root dir: `/home/<you>/jenkins-agent`
- **Labels: `linux`**  (the pipelines pin to this)
- Launch method: **Launch agent by connecting it to the controller** (inbound/JNLP — no sshd needed)
- Save, open the node page, copy the shown `java -jar agent.jar …` command (it has the URL + secret).

**Step 3 — start the agent inside WSL2**:
```bash
mkdir -p ~/jenkins-agent && cd ~/jenkins-agent
# paste the command from the node page, e.g.:
curl -sO http://<jenkins-host>:8080/jnlpJars/agent.jar
java -jar agent.jar -url http://<jenkins-host>:8080/ -secret <SECRET> -name wsl-linux -workDir ~/jenkins-agent
```
> Reaching the Windows-hosted Jenkins from WSL2: on Windows 11 with mirrored networking `localhost`
> works; otherwise use the Windows host IP. The node page prints the exact URL.

**Step 4 — pin the pipelines to the Linux agent**: change `agent any` → `agent { label 'linux' }` in
`jenkins/Jenkinsfile.ci` and `jenkins/Jenkinsfile.aws` (or set the Built-In Node's executors to 0 so
`agent any` can only land on `wsl-linux`).

**Step 5 — tools must be OS-portable**: in Manage Jenkins → Tools, `jdk17` and `maven3` must use
**Install automatically** (Temurin / Apache Maven installers), *not* a hard `C:\…` path — otherwise
they won't resolve on the Linux agent. Jenkins provisions them onto the WSL node on first build.

Enterprise Jenkins almost always uses Linux agents, which is exactly this setup.

---

## 8. Optional hardening (interview talking points)
- **Quality gate**: after `sonar:sonar`, add a `waitForQualityGate()` stage (needs a SonarQube webhook
  back to Jenkins) to fail the build on gate breach.
- **Shared Library**: factor the image build/push loop into a `vars/buildAndPush.groovy` step to DRY
  across services/pipelines.
- **Agents as pods**: the Kubernetes plugin can run each build in an ephemeral pod (closest to GHA's
  ephemeral runners) — a `podTemplate` with `maven`, `docker`/kaniko, `awscli` containers.
- **Approvals**: wrap the EKS deploy in an `input` step for a manual gate before touching the cluster.
