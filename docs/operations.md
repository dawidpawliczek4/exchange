# Operations

How to run, deploy, and reset the stack. The **`justfile` is the canonical entry point**
for anything operational — `just` (no args) lists every recipe with a one-line
description. The Gradle/Compose commands below are what the recipes expand to.

## Local development (no containers except Kafka/Postgres)

```bash
docker compose -f devops/docker-compose.yml up -d kafka postgres
./gradlew :matching-service:run     # plain JVM app, Kafka at localhost:9092
./gradlew :app:bootRun              # Spring gateway on :8080
./gradlew :agent-crowd:run          # bot crowd, Kafka only; runs until Ctrl+C
```

The gateway needs **both** Kafka and Postgres: Flyway runs `V1__init.sql` and Hibernate
validates the schema at boot. The matching service needs only Kafka and writes its WAL to
`./journal.bin` in the working directory. The crowd needs only Kafka (and a running
matching service to trade against); it is not part of the Compose stack or the k8s
manifests yet, so this is the only way to run it. Crowd knobs, all env vars with
defaults: `BOT_COUNT` (100), `MID_PRICE` (10000), `PRICE_BAND` (100), `SEED` (42).

Build/test:

```bash
./gradlew build        # compile + test + spotlessCheck (what CI runs)
./gradlew spotlessApply
just build / just test / just clean
```

## Docker Compose (full local stack)

```bash
just compose-up       # kafka + matching + gateway + postgres + prometheus + grafana
just demo             # register a user, post two crossing orders (needs jq)
just compose-down     # stop, keep volumes
just compose-reset    # stop and wipe ALL volumes — journal, pgdata, kafkadata
```

Six services (`devops/docker-compose.yml`, project name `exchange`): Kafka
(`apache/kafka`, KRaft single node, host port 9092 / internal `kafka:29092`), `matching`
(built from `devops/matching-service.Dockerfile`, metrics on 9400, WAL on the `journal`
volume at `/data`, `restart: on-failure`), `gateway` (`devops/app.Dockerfile`, :8080,
waits for Kafka + Postgres healthchecks), `postgres` (:5432, db/user/password all
`exchange`), `prometheus` (:9090), `grafana` (:3000, anonymous viewer).

Five named volumes: `journal`, `pgdata`, `kafkadata`, `promdata`, `grafanadata`. The
journal volume is why the book survives container restarts — and why upgrading across an
incompatible WAL format change requires `just compose-reset` (or `down -v`).

Kafka bootstrap overrides (all default to `localhost:9092`):

| Service | Env var |
|---|---|
| matching service | `KAFKA_BOOTSTRAP_SERVERS` |
| agent crowd | `KAFKA_BOOTSTRAP_SERVERS` |
| gateway | `SPRING_KAFKA_BOOTSTRAP_SERVERS` |

Gateway database config: `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD`
(default `jdbc:postgresql://localhost:5432/exchange`, `exchange`/`exchange`).

## Kubernetes

Manifests live in `devops/k8s/` as a kustomize base with `local` and `gcp` overlays.
Base: three namespaces (`kafka`, `exchange`, `monitoring`), Strimzi-managed Kafka
(`KafkaNodePool` + `Kafka` named `exchange-kafka`) plus the two `KafkaTopic`s, Postgres
StatefulSet, `matching` StatefulSet (journal as a `volumeClaimTemplate`), `gateway`
Deployment, Prometheus, Grafana.

Two things are *not* in kustomize:

- The **Strimzi operator** — installed imperatively by `just strimzi` (version pinned at
  the top of the justfile).
- The **Grafana dashboard ConfigMap** — generated from `devops/grafana/dashboards/` by
  `just monitoring` / `just gcp-up` (`kubectl create configmap --dry-run | apply`), so
  the dashboard JSON stays single-sourced for Compose and k8s.

### Local (kind)

```bash
just up       # cluster → namespaces → postgres → strimzi → kafka → images → load → apps → monitoring
just status
just down     # delete the kind cluster
kubectl port-forward svc/gateway -n exchange 8080:80   # then drive it as under Compose
```

### GCP (GKE)

Cluster and registry provisioning is **Terraform** (`infra/gcp/`): enables the APIs,
creates the Artifact Registry repo, a custom VPC with secondary ranges for pods/services,
and a 3-node `e2-medium` GKE Standard cluster. Outputs include `registry_url` and the
`get_credentials_command` to point kubectl at the cluster.

```bash
cd infra/gcp
terraform init
terraform apply -var project_id=<your-project>   # region/zone default to europe-central2(-a)
$(terraform output -raw get_credentials_command)  # point kubectl at the cluster
gcloud auth configure-docker $(terraform output -raw registry_url | cut -d/ -f1)
```

Deploying is the justfile again:

```bash
just gcp-push   # build both images for linux/amd64, push to Artifact Registry
just gcp-up     # Strimzi + kubectl apply -k devops/k8s/overlays/gcp, waits for rollouts
```

`gcp-up` refuses to run unless the current kubectl context is a GKE one. The gateway is
patched to a LoadBalancer in the gcp overlay — grab its `EXTERNAL-IP` and drive it on
port 80.

**Registry/tag duplication**: the image registry and tag are hardcoded in *two* places
that must stay in sync — `justfile` (`gcp_registry`, `gcp_tag`) and
`devops/k8s/overlays/gcp/kustomization.yaml` (`newName`/`newTag` for both images).
Bumping a version means changing both.

A running cluster, its load balancer, and the registry cost real money — `terraform
destroy` when done.

## Resetting state

| What | How |
|---|---|
| Everything under Compose | `just compose-reset` |
| Just the WAL (local dev) | delete `journal.bin` |
| kind cluster | `just down` |
| GCP | `terraform destroy` in `infra/gcp/` |
