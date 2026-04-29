# TrustGrid

TrustGrid is a backend marketplace trust-and-safety API for participants, listings, transactions, evidence, disputes, reviews, reputation, risk, ranking, operations, policy, incidents, lineage, repair, and capability governance.

## Current Scope

- Spring Boot API service: `trust-grid-api`
- Java package root: `com.trustgrid.api`
- Maven artifact: `trust-grid-api`
- Database: `trustgrid`
- Runtime services: Postgres, Redis, Kafka, OpenSearch, and MinIO through Docker Compose

TrustGrid models marketplace trust decisions with deterministic rules, explicit mutation endpoints, audit records, and Postgres-backed integration tests.

## Test Commands

```bash
cd apps/api
./mvnw test
./mvnw -DskipTests compile
```

## Docker Compose Validation

```bash
docker compose -f infra/docker-compose/docker-compose.yml config
```

## Local Health Check

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/v1/system/ping
```
