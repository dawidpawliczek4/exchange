strimzi_version := "1.1.0"
cluster := "exchange"

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

# Bring up the whole stack (Kafka + matching + gateway)
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
    kubectl apply -f devops/k8s/namespaces.yaml

# Create postgres db
postgres:
    kubectl apply -f devops/k8s/namespaces.yaml
    kubectl apply -f devops/k8s/postgres.yaml

# Install the Strimzi operator (pinned) into the kafka namespace
strimzi:
    kubectl apply -f devops/k8s/namespaces.yaml
    curl -sL https://github.com/strimzi/strimzi-kafka-operator/releases/download/{{strimzi_version}}/strimzi-cluster-operator-{{strimzi_version}}.yaml \
      | sed 's/namespace: .*/namespace: kafka/' \
      | kubectl apply -n kafka -f -
    kubectl wait --for=condition=Available deploy/strimzi-cluster-operator -n kafka --timeout=300s

# Bring up the Kafka cluster
kafka:
    kubectl apply -f devops/k8s/kafka.yaml
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
    kubectl apply -f devops/k8s/namespaces.yaml
    kubectl apply -f devops/k8s/gateway.yaml -f devops/k8s/matching.yaml
    kubectl rollout status deploy/gateway -n exchange --timeout=180s
    kubectl rollout status statefulset/matching -n exchange --timeout=180s

# Deploy Prometheus + Grafana (dashboard ConfigMap generated from devops/grafana/dashboards/)
monitoring:
    kubectl apply -f devops/k8s/namespaces.yaml
    kubectl create configmap grafana-dashboards -n monitoring \
      --from-file=devops/grafana/dashboards/ \
      --dry-run=client -o yaml | kubectl apply -f -
    kubectl apply -f devops/k8s/prometheus.yaml -f devops/k8s/grafana.yaml
    kubectl rollout status deploy/prometheus deploy/grafana -n monitoring --timeout=180s

# Show cluster state (Kafka layer + apps)
status:
    kubectl get kafka,kafkanodepool,kafkatopic,pods -n kafka
    kubectl get pods,statefulset,deploy -n exchange
    kubectl get pods,deploy -n monitoring

# Delete the kind cluster (everything goes)
down:
    kind delete cluster --name {{cluster}}
