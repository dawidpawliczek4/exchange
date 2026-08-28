# exchange

A small trading-exchange matching engine, built as a learning project.

I wanted to understand two things:

1. How an exchange actually works
2. How distributed systems work

---

### Modules

| Module | Language             | Role                              |
|---|----------------------|-----------------------------------|
| `:contracts` |  Java            | The wire: shared types + codec.   |
| `:engine` |  Java            | The core.                         |
| `:matching-service` | Kotlin               | Runs the engine off Kafka.        |
| `:app` | Kotlin + Spring Boot | Gateway: REST in, trades out.     |
| `:benchmark` | Java + JMH           | Measures the engine.              |
| `:e2e` | Kotlin               | Full-stack test (Testcontainers). |

Orders flow `gateway → orders.commands → matching-service → orders.trades → gateway`. The gateway
validates and publishes; the matching service consumes commands, matches them in the engine, and
publishes the trades back; the gateway fans those out over WebSocket.

Deeper documentation lives in [docs/](docs/README.md) — architecture, operations,
benchmarking, and the decision records ([ADRs](docs/adr/README.md)).

---

## Running it

Brings up Kafka, the matching service, and the gateway (requires Docker):

```bash
docker compose -f devops/docker-compose.yml up -d --build
```

Then drive it — or run `just demo` for the whole flow in one command (needs `jq` and `websocat`):

```bash
# Watch trades over WebSocket
websocat ws://localhost:8080/marketdata

# Register a user → { "accessToken": "...", "refreshToken": "..." }
TOKEN=$(curl -sX POST localhost:8080/auth/credentials/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"trader@example.com","password":"password123"}' | jq -r .accessToken)

# POST two crossing orders as that user (self-trade; register a second user for real maker/taker)
curl -X POST localhost:8080/order -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"side":"BUY","price":100,"market":false,"quantity":5}'
curl -X POST localhost:8080/order -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"side":"SELL","price":100,"market":false,"quantity":5}'
  
# → the trade shows up in the websocat

# Shut down
docker compose -f devops/docker-compose.yml down
```

### Tests & benchmarks

Requires JDK 25 (the Gradle toolchain fetches it if missing).

```bash
./gradlew test            # all module tests
./gradlew :benchmark:jmh  # JMH benchmarks — runs BOTH harnesses; see docs/benchmarking.md first
```

### Local development

To iterate on a service without rebuilding its image, bring up the containers it needs and run it
from Gradle (Kafka defaults to `localhost:9092`):

```bash
docker compose -f devops/docker-compose.yml up -d kafka postgres
./gradlew :matching-service:run   # needs Kafka
./gradlew :app:bootRun            # needs Kafka and Postgres
```

## Kubernetes

The same stack also runs on Kubernetes: Strimzi-managed Kafka, the two services, Postgres, and the
monitoring pair. Manifests live in `devops/k8s/` as a kustomize base with `local` and `gcp` overlays.

### Local (kind)

Requires `docker`, `kind`, `kubectl`, and `just`:

```bash
just up      # kind cluster → namespaces → Postgres → Strimzi → Kafka → build images → load → apps → monitoring
just status  # everything green?
just down    # delete the cluster
```

The gateway service is ClusterIP; to drive it, forward it to localhost and reuse the curl/websocat
block from [Running it](#running-it):

```bash
kubectl port-forward svc/gateway -n exchange 8080:80
```

### GCP (GKE)

You need `gcloud`, `terraform`, `kubectl`, `docker`, `just`, and a GCP project with billing
enabled.

Provisioning is Terraform (`infra/gcp/`): it enables the APIs and creates the Artifact Registry
repo, a custom VPC, and a GKE Standard cluster with three `e2-medium` nodes (pod requests total
~2 CPU / ~4Gi — with fewer nodes, some pods stay `Pending`):

```bash
cd infra/gcp
terraform init
terraform apply -var project_id=your-project      # region/zone default to europe-central2(-a)
$(terraform output -raw get_credentials_command)   # point kubectl at the cluster
gcloud auth configure-docker europe-central2-docker.pkg.dev
```

kubectl talks to GKE through a plugin; install it once if you don't have it:

```bash
gcloud components install gke-gcloud-auth-plugin
```

Then point the repo at *your* registry — the values are hardcoded in two places and must match:

- `justfile` (top): `gcp_registry` → `$REGION-docker.pkg.dev/$PROJECT_ID/exchange`, and `gcp_tag`
- `devops/k8s/overlays/gcp/kustomization.yaml`: both `newName` entries (gateway, matching) and their
  `newTag`

Bumping a version means changing the tag in both files. Deploy:

```bash
just gcp-push   # build both images for linux/amd64 and push them
just gcp-up     # Strimzi + `kubectl apply -k devops/k8s/overlays/gcp`, waits for Kafka and rollouts
```

`gcp-up` refuses to run unless the current kubectl context is a GKE one, and finishes by printing
the gateway service — grab its `EXTERNAL-IP` (the overlay patches the gateway to a LoadBalancer) and
drive it on port 80:

```bash
IP=$(kubectl get svc gateway -n exchange -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
curl -X POST http://$IP/auth/credentials/register ...   # same flow as in Running it
websocat ws://$IP/marketdata
```

Prometheus and Grafana stay ClusterIP — `kubectl port-forward` them if you want the dashboard.

A running cluster, its load balancer, and the registry all cost real money. Tear down with:

```bash
cd infra/gcp && terraform destroy -var project_id=your-project
```

### Observability

The matching service exposes Micrometer/Prometheus metrics on `:9400/metrics` (order throughput, JVM,
Kafka). Both Compose and the Kubernetes stack add Prometheus (`localhost:9090` under Compose) and
Grafana (`localhost:3000`, no login) with an orders/s dashboard provisioned from `devops/` — nothing
to click. Post orders with `just demo` and watch the curve.

### Configuration

The matching service persists every order to a write-ahead log (`journal.bin`) and rebuilds its book
from it on restart. Under Compose this lives on the `journal` volume, so it survives container
restarts.

Kafka bootstrap defaults to `localhost:9092`; Compose wires the services to the broker at
`kafka:29092`. Override per service:

- matching service — `KAFKA_BOOTSTRAP_SERVERS`
- gateway — `SPRING_KAFKA_BOOTSTRAP_SERVERS`

---
## Tech stack

Java 25 · Kotlin · Spring Boot 4 · Apache Kafka · Micrometer + Prometheus + Grafana · Gradle
(multi-module, version catalog, convention plugins) · JMH · JUnit 5.
