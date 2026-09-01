strimzi_version := "1.1.0"
cluster := "exchange"
gcp_registry := "europe-central2-docker.pkg.dev/exchange-dawid-2026/exchange"
gcp_tag := "v1"

default:
    @just --list

# --- Gradle ------------------------------------------------------------------

# Build everything with Gradle
build:
    ./gradlew build

# Run tests/checks
test:
    ./gradlew check

# Clean build artifacts
clean:
    ./gradlew clean

# --- Docker Compose (local stack) --------------------------------------------

# Bring up the whole stack (Kafka + matching + gateway + bot crowd)
compose-up:
    docker compose -f devops/docker-compose.yml up -d --build

# Stop the stack
compose-down:
    docker compose -f devops/docker-compose.yml down

# Stop the stack and wipe all volumes (journal, pgdata, kafkadata) — fresh state on next up
compose-reset:
    docker compose -f devops/docker-compose.yml down -v

# Register a demo user and place two crossing orders (needs jq; run `just compose-up` first)
demo:
    #!/usr/bin/env bash
    set -euo pipefail
    token=$(curl -sX POST localhost:8080/auth/credentials/register \
      -H 'Content-Type: application/json' \
      -d "{\"email\":\"demo+$RANDOM@example.com\",\"password\":\"password123\"}" | jq -r .accessToken)
    for side in BUY SELL; do
      curl -sX POST localhost:8080/order -H "Authorization: Bearer $token" \
        -H 'Content-Type: application/json' \
        -d "{\"side\":\"$side\",\"price\":100,\"market\":false,\"quantity\":5}"
    done
    echo "posted two crossing orders — watch: websocat ws://localhost:8080/marketdata"

# --- Kubernetes (kind + Strimzi) ---------------------------------------------

# Full bootstrap from scratch: kind + Strimzi + Kafka + gateway + matching + monitoring
up: cluster namespaces postgres strimzi kafka images load apps monitoring

# Create the kind cluster
cluster:
    kind create cluster --name {{cluster}}

# Create namespaces used by apps
namespaces:
    kubectl apply -f devops/k8s/base/namespaces.yaml

# Create postgres db
postgres:
    kubectl apply -f devops/k8s/base/namespaces.yaml
    kubectl apply -f devops/k8s/base/postgres.yaml

# Install the Strimzi operator (pinned) into the kafka namespace
strimzi:
    kubectl apply -f devops/k8s/base/namespaces.yaml
    curl -sL https://github.com/strimzi/strimzi-kafka-operator/releases/download/{{strimzi_version}}/strimzi-cluster-operator-{{strimzi_version}}.yaml \
      | sed 's/namespace: .*/namespace: kafka/' \
      | kubectl apply -n kafka -f -
    kubectl wait --for=condition=Available deploy/strimzi-cluster-operator -n kafka --timeout=300s

# Bring up the Kafka cluster
kafka:
    kubectl apply -f devops/k8s/base/kafka.yaml
    kubectl wait kafka/exchange-kafka -n kafka --for=condition=Ready --timeout=300s

# Build both service images
images:
    docker build -f devops/app.Dockerfile -t exchange-gateway:dev .
    docker build -f devops/matching-service.Dockerfile -t exchange-matching:dev .

# Load the service images into the kind cluster
load:
    kind load docker-image exchange-gateway:dev exchange-matching:dev --name {{cluster}}

# Deploy gateway + matching and wait for them
apps:
    kubectl apply -f devops/k8s/base/namespaces.yaml
    kubectl apply -f devops/k8s/base/gateway.yaml -f devops/k8s/base/matching.yaml
    kubectl rollout status deploy/gateway -n exchange --timeout=180s
    kubectl rollout status statefulset/matching -n exchange --timeout=180s

# Deploy Prometheus + Grafana (dashboard ConfigMap generated from devops/grafana/dashboards/)
monitoring:
    kubectl apply -f devops/k8s/base/namespaces.yaml
    kubectl create configmap grafana-dashboards -n monitoring \
      --from-file=devops/grafana/dashboards/ \
      --dry-run=client -o yaml | kubectl apply -f -
    kubectl apply -f devops/k8s/base/prometheus.yaml -f devops/k8s/base/grafana.yaml
    kubectl rollout status deploy/prometheus deploy/grafana -n monitoring --timeout=180s

# Show cluster state (Kafka layer + apps)
status:
    kubectl get kafka,kafkanodepool,kafkatopic,pods -n kafka
    kubectl get pods,statefulset,deploy -n exchange
    kubectl get pods,deploy -n monitoring

# Delete the kind cluster (everything goes)
down:
    kind delete cluster --name {{cluster}}

# --- GCP (GKE + Artifact Registry) --------------------------------------------
# Assumes the GKE cluster exists and kubectl points at it (gcloud container clusters get-credentials ...)

_gcp-context:
    @kubectl config current-context | grep -q '^gke_' || { echo "current kubectl context is not GKE — run: gcloud container clusters get-credentials <cluster> --region <region>"; exit 1; }

# Build both images for GKE (linux/amd64), tagged for Artifact Registry
gcp-build:
    docker build --platform linux/amd64 -f devops/app.Dockerfile -t {{gcp_registry}}/gateway:{{gcp_tag}} .
    docker build --platform linux/amd64 -f devops/matching-service.Dockerfile -t {{gcp_registry}}/matching:{{gcp_tag}} .

# Build and push both images to Artifact Registry
gcp-push: gcp-build
    docker push {{gcp_registry}}/gateway:{{gcp_tag}}
    docker push {{gcp_registry}}/matching:{{gcp_tag}}

# Deploy everything to GKE: Strimzi + gcp overlay (Kafka, topics, apps, monitoring)
gcp-up: _gcp-context strimzi
    kubectl create configmap grafana-dashboards -n monitoring \
      --from-file=devops/grafana/dashboards/ \
      --dry-run=client -o yaml | kubectl apply -f -
    kubectl apply -k devops/k8s/overlays/gcp
    kubectl wait kafka/exchange-kafka -n kafka --for=condition=Ready --timeout=300s
    kubectl rollout status deploy/gateway -n exchange --timeout=300s
    kubectl rollout status statefulset/matching -n exchange --timeout=300s
    kubectl rollout status deploy/prometheus deploy/grafana -n monitoring --timeout=180s
    kubectl get svc gateway -n exchange
