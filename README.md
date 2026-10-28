# FurniFlow

Order and stock platform for a furniture business: online shop, B2B clients and admin compete for the same stock without overselling.

```
Angular + Bootstrap ──► gateway :8090 ──► catalog-service   :8091  (MongoDB)
                                     ├──► inventory-service :8092  (PostgreSQL) ◄──┐ reserve (HTTP)
                                     └──► order-service     :8093  (PostgreSQL) ───┘ order-paid (Kafka)
```

**Stack:** Java 25, Spring Boot 4.1, Spring Cloud Gateway, Spring Data JPA + Flyway, Spring Data MongoDB, Spring for Apache Kafka, Angular 22, Bootstrap 5, Testcontainers, GitHub Actions.

## Run locally

Needs JDK 25, Node 24 and Docker.

```bash
docker compose up -d                          # PostgreSQL x2, MongoDB, Kafka
./mvnw -pl inventory-service spring-boot:run  # one terminal per service (gateway, catalog-service, order-service too)
cd frontend && npm install && npm start       # http://localhost:4200
```

On Windows PowerShell use `.\mvnw.cmd` instead of `./mvnw`.

## Test

```bash
./mvnw verify                          # all services; Testcontainers starts real databases in Docker
cd frontend && npx ng test --watch=false
```

## Layout

| Path | What |
|---|---|
| `gateway/` | Spring Cloud Gateway, routes `/api/{catalog,inventory,orders}/**` |
| `catalog-service/` | Product catalog in MongoDB (furniture has varying attributes) |
| `inventory-service/` | Stock and reservations in PostgreSQL (needs transactions) |
| `order-service/` | Orders in PostgreSQL |
| `frontend/` | Angular storefront and admin dashboard |
| `compose.yaml` | Local databases and Kafka |
| `.github/workflows/ci.yml` | Backend and frontend CI |

## Roadmap

- [x] 1. Inventory: stock reservations, the overselling experiment (naive vs optimistic vs `FOR UPDATE` vs conditional `UPDATE`)
- [x] 2. Orders: idempotency keys, transactional outbox → Kafka, idempotent consumer
- [ ] 3. Security: Keycloak, Spring Security resource servers, Angular login + role guards
- [ ] 4. Catalog in MongoDB, Angular storefront and admin pages
- [ ] 5. Dockerfiles, Kubernetes manifests, cloud deploy
