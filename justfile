
image := "exchange:dev"
dockerfile := "devops/Dockerfile"

default:
    @just --list

# --- Gradle (kod aplikacji) --------------------------------------------------

# Zbuduj aplikację (jar) Gradle'em
build:
    ./gradlew build

# Uruchom wszystkie testy/checki
test:
    ./gradlew check

# Wyczyść artefakty buildu
clean:
    ./gradlew clean

# --- Docker / obraz ----------------------------------------------------------

# Zbuduj obraz Dockera PROSTO do minikube (bez pushowania do rejestru).
minikube-build:
    minikube image build -f {{dockerfile}} -t {{image}} .

docker-build:
    docker build -f {{dockerfile}} -t {{image}} .

docker-run:
    docker run --rm -p 8080:8080 {{image}}

# --- Kubernetes --------------------------------------------------------------

# Wgraj manifesty na klaster (deployment + service).
deploy:
    kubectl apply -f devops/deployment.yml -f devops/service.yml

# Pełny cykl: zbuduj obraz, wgraj manifesty, wymuś świeży rollout podów.
redeploy: docker-build deploy
    kubectl rollout restart deployment/exchange
    kubectl rollout status deployment/exchange

# Usuń aplikację z klastra (zostawia sam klaster nietknięty)
undeploy:
    kubectl delete -f devops/deployment.yml -f devops/service.yml

# Pokaż stan: pody, service, endpointy
status:
    kubectl get deployment,pods,service,endpoints -l app=exchange

# Streamuj logi ze wszystkich podów aplikacji (Ctrl+C kończy)
logs:
    kubectl logs -f -l app=exchange --all-containers --prefix

# Port-forward Service → localhost:8080 (Ctrl+C kończy).
forward:
    kubectl port-forward service/exchange 8080:80

# --- minikube (klaster) ------------------------------------------------------

# Wystartuj klaster minikube (jeśli nie działa)
cluster-up:
    minikube start

# Stan klastra minikube
cluster-status:
    minikube status

# Zatrzymaj klaster (stan zostaje, można wrócić przez cluster-up)
cluster-down:
    minikube stop
