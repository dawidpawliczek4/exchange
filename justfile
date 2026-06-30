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

# --- Kubernetes (kind + Strimzi) ---------------------------------------------

# Full bootstrap from scratch: kind + Strimzi + Kafka + gateway + matching
up: cluster strimzi kafka images load apps

# Create the kind cluster
cluster:
    kind create cluster --name {{cluster}}

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

# Show cluster state (Kafka layer + apps)
status:
    kubectl get kafka,kafkanodepool,kafkatopic,pods -n kafka
    kubectl get pods,statefulset,deploy -n exchange

# Delete the kind cluster (everything goes)
down:
    kind delete cluster --name {{cluster}}
