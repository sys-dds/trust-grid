# TrustGrid

TrustGrid is a peer-to-peer marketplace trust-and-safety backend for marketplace participants, trust profiles, risk-aware operations, and future dispute/reputation workflows.

## Local runtime

```bash
docker compose -f infra/docker-compose/docker-compose.yml up -d --build
```

## Health checks

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health/readiness
curl http://localhost:8080/api/v1/system/ping
curl http://localhost:8080/api/v1/system/node
curl http://localhost:8080/api/v1/system/dependencies
```

## Tests

```bash
cd apps/api
./mvnw test
```

## Docker Compose validation

```bash
docker compose -f infra/docker-compose/docker-compose.yml config
```

## Current scope

This first slice only bootstraps the backend/runtime foundation. Listings, transactions, disputes, reputation, fraud, ranking, operations, and payment-boundary events come later.

## Project boundary

TrustGrid is focused on peer-to-peer marketplace trust and safety. It does not execute payments, implement a financial ledger, implement deployment orchestration, implement live collaboration, or implement social content feeds.
