# FurniFlow

Order and stock platform for a furniture business: online shop, B2B clients and admin compete for the same stock without overselling.

```
Angular + Bootstrap ──► gateway :8090 ──► catalog-service   :8091  (MongoDB)    ◄──┐ products (HTTP)
                                     ├──► inventory-service :8092  (PostgreSQL) ◄──┤ reserve (HTTP)
                                     └──► order-service     :8093  (PostgreSQL) ───┘ order-paid (Kafka)
```

**Stack:** Java 25, Spring Boot 4.1, Spring Cloud Gateway, Spring Data JPA + Flyway, Spring Data MongoDB, Spring for Apache Kafka, Spring Security (OAuth2 resource servers), Keycloak, Angular 22, Bootstrap 5, Testcontainers, Docker, Kubernetes, GitHub Actions.

## Run locally

Needs JDK 25, Node 24 and Docker.

```bash
docker compose up -d                          # PostgreSQL x2, MongoDB, Kafka, Keycloak (:8180)
./mvnw -pl inventory-service spring-boot:run  # one terminal per service (gateway, catalog-service, order-service too)
cd frontend && npm install && npm start       # http://localhost:4200
```

On Windows PowerShell use `.\mvnw.cmd` instead of `./mvnw`.

## Run on Kubernetes

Needs Docker Desktop with Kubernetes enabled. Stop compose first (`docker compose stop`), both use port 8180.

```bash
for s in gateway catalog-service inventory-service order-service; do docker build --build-arg SERVICE=$s -t ghcr.io/erjonfida/furniflow/$s .; done
docker build -t ghcr.io/erjonfida/furniflow/frontend frontend
kubectl apply -k .                            # then http://localhost
```

## Deploy to one server (AWS Lightsail)

`k8s/lightsail` runs the same manifests on a 4 GB Ubuntu server with k3s, over HTTPS (Let's Encrypt), with Keycloak under `/auth`. It uses the images CI published from main.

1. Get a name for the server, e.g. a free subdomain at [duckdns.org](https://www.duckdns.org).
2. In Lightsail, create an Ubuntu 24.04 instance on the 4 GB plan and attach a static IP. On its Networking tab, allow HTTPS (443) next to HTTP and SSH. Point the name at the static IP.
3. Connect with SSH and run, with your name in place of `yourname.duckdns.org`:

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
curl -sfL https://get.k3s.io | sh -s - --disable metrics-server --write-kubeconfig-mode 644
git clone https://github.com/ErjonFida/FurniFlow.git && cd FurniFlow
# Random passwords, kept only in the cluster
kubectl create secret generic furniflow $(for k in db-password order-service-client-secret keycloak-admin-password admin-password; do echo --from-literal=$k=$(openssl rand -hex 16); done)
kubectl kustomize --load-restrictor LoadRestrictionsNone k8s/lightsail | sed "s/PUBLIC_HOST/yourname.duckdns.org/g" | kubectl apply -f -
kubectl get pods -w                           # the first start takes several minutes
```

The shop is then at `https://yourname.duckdns.org`. The realm's admin password: `kubectl get secret furniflow -o jsonpath='{.data.admin-password}' | base64 -d`. To pick up newer images: `kubectl rollout restart deployment`.

## Test

```bash
./mvnw verify                          # all services; Testcontainers starts real databases in Docker
cd frontend && npx ng test --watch=false
```

## Layout

| Path | What |
|---|---|
| `gateway/` | Spring Cloud Gateway, routes `/api/{catalog,inventory,orders}/**` |
| `catalog-service/` | Product catalog in MongoDB (furniture has varying attributes). The first start adds demo products, with photos from [Unsplash](https://unsplash.com) |
| `inventory-service/` | Stock and reservations in PostgreSQL (needs transactions) |
| `order-service/` | Orders in PostgreSQL |
| `frontend/` | Angular storefront and admin dashboard |
| `keycloak/` | Realm imported by compose: roles, clients and test users. Anyone may register as a customer. Admins add staff in Keycloak's console (Admin → Users and roles) and must sign in with an authenticator app; the first sign-in asks to set one up |
| `compose.yaml` | Local databases, Kafka and Keycloak |
| `Dockerfile` | Image for any Spring service (`--build-arg SERVICE=...`) |
| `k8s/`, `kustomization.yaml` | Kubernetes: stand-ins for the databases, Kafka and Keycloak, then the services |
| `k8s/lightsail/` | The same on one public server: HTTPS, Keycloak on its own database, memory sized for 4 GB |
| `.github/workflows/ci.yml` | Backend and frontend CI; publishes the images from main |

## Roadmap

- [x] 1. Inventory: stock reservations, the overselling experiment (naive vs optimistic vs `FOR UPDATE` vs conditional `UPDATE`)
- [x] 2. Orders: idempotency keys, transactional outbox → Kafka, idempotent consumer
- [x] 3. Security: Keycloak, Spring Security resource servers, Angular login + role guards
- [x] 4. Catalog in MongoDB, Angular storefront and admin pages
- [x] 5. Dockerfiles, Kubernetes manifests, images published by CI
- [ ] 6. Cloud deploy
