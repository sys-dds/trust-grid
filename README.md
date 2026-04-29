# TrustGrid

TrustGrid is a backend marketplace trust-and-safety control plane for participants, listings, transactions, evidence, disputes, reviews, reputation, risk, ranking, incidents, lineage, repair, and capability governance.

## What Problem It Solves

Marketplaces do not only need CRUD for listings. They need to decide whether people and listings are trustworthy enough for specific actions, explain those decisions, detect abuse, preserve audit trails, and keep derived trust data consistent. TrustGrid models those trust-bearing workflows directly.

## Why This Is Not A CRUD Marketplace

TrustGrid stores marketplace entities, but the interesting work is the control plane around them: deterministic risk decisions, evidence-aware disputes, review abuse detection, trust-aware ranking, policy simulation, incident replay, lineage rebuilds, consistency checks, repair recommendations, and action-level capability decisions.

## Core Capabilities

- Participants, public profiles, capabilities, verification, account status, restrictions, and trust summaries.
- Categories, listings, publish/search/moderation gates, duplicate detection, and trust-ranked search.
- Transactions with lifecycle invariants, idempotency, timelines, risk gates, and outbox events.
- Evidence metadata, evidence requirements, dispute flows, evidence bundles, and dispute outcomes.
- Reviews, reputation snapshots, recalculation, trust tier transitions, and deterministic explanations.
- Review abuse graph detection for reciprocal reviews, rings, low-value farming, similar text, bursts, suppression, and trust graph risk.
- Ops queues, moderator actions, audit logs, manual review, safety escalation, and account restriction workflows.
- Payment-boundary events only: funds release requested, refund requested, payout hold requested, and transaction closed. No payment execution.
- Versioned policy engine with DSL-lite rules, approvals, exceptions, rollback, blast-radius preview, simulation, and explainability.
- Trust telemetry, SLOs, monitors, incidents, internal alerts, dashboard aggregates, evidence bundles, metrics, and replay.
- Trust score lineage, ranking lineage, policy lineage, consistency verifiers, repair recommendations, and audited operator repair actions.
- Capability governance: action-specific policies, deterministic simulation, deny reasons, next steps, temporary grants, break-glass overrides, expiry, and timelines.

## Architecture Overview

TrustGrid is a Spring Boot modular monolith using JDBC and Flyway over Postgres. Redis, Kafka, OpenSearch, and MinIO are runtime dependencies in Docker Compose, but core tests keep deterministic behavior in Postgres-backed integration tests. The API writes domain events to an outbox-style `marketplace_events` table and keeps read/rebuild/replay endpoints explicit.

## Tech Stack

- Java 21
- Spring Boot 3.5
- Maven Wrapper
- JDBC, no JPA/Hibernate
- Flyway migrations
- Postgres
- Redis, Kafka, OpenSearch, MinIO through Docker Compose
- Testcontainers for integration tests

## Local Run

```bash
docker compose -f infra/docker-compose/docker-compose.yml up -d --build
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/v1/system/ping
```

Stop and clean local runtime:

```bash
docker compose -f infra/docker-compose/docker-compose.yml down -v
```

## Test Commands

```bash
cd apps/api
./mvnw test
./mvnw -DskipTests compile
cd ../..
docker compose -f infra/docker-compose/docker-compose.yml config
```

Focused final slice:

```bash
cd apps/api
./mvnw "-Dtest=Pr8OperationalHardeningIntegrationTest,CapabilityGovernancePolicyIntegrationTest,CapabilitySimulationIntegrationTest,TemporaryCapabilityGrantIntegrationTest,BreakGlassGovernanceIntegrationTest,CapabilityAccessRegressionIntegrationTest,FinalCapstoneInvariantRegressionIntegrationTest,FinalCapstonePerformanceProofIntegrationTest,FullDockerDemoScenarioIntegrationTest,FinalDocumentationPackagingSmokeTest" test
```

## Demo Flow

The guided demo is in [docs/demo/demo-script.md](docs/demo/demo-script.md). A small health-oriented shell helper is available at [scripts/demo/trustgrid-demo.sh](scripts/demo/trustgrid-demo.sh).

## Key API Surfaces

- `/api/v1/participants`
- `/api/v1/listings`
- `/api/v1/listings/trust-ranked-search`
- `/api/v1/transactions/{transactionId}/disputes`
- `/api/v1/participants/{participantId}/reputation/recalculate`
- `/api/v1/review-graph/*`
- `/api/v1/ops/*`
- `/api/v1/transactions/{transactionId}/payment-boundary/*`
- `/api/v1/policy-engine/*`
- `/api/v1/policy-simulations/*`
- `/api/v1/trust-monitors/run`
- `/api/v1/trust-incidents`
- `/api/v1/lineage/rebuild/full`
- `/api/v1/consistency/checks/full`
- `/api/v1/data-repair/recommendations`
- `/api/v1/capability-governance/*`

## System Design Trade-Offs

TrustGrid favors deterministic rules over opaque models, explicit mutation endpoints over hidden read-side writes, bounded simulation over unbounded analysis, and audited operator action over automatic repair. It is intentionally a backend control plane, not a frontend product.

## Non-Overlap Boundaries

TrustGrid is not Kay Ledger: it emits payment-boundary events but does not move money or maintain a financial ledger.

TrustGrid is not MatchGraph: it has trust-aware ranking, but it is not a general recommendation platform.

TrustGrid is not SyncForge: it does not implement realtime collaboration.

TrustGrid is not DeployForge: it does not manage infrastructure deployments.

TrustGrid is not a DLP redirect platform: it does not build short-link or redirect infrastructure.

## Interview Stories

The interview story pack is in [docs/interview/interview-story-pack.md](docs/interview/interview-story-pack.md). The short version: TrustGrid demonstrates marketplace trust modeling, deterministic policy decisions, replayable ranking, abuse detection without ML, operational incidents, lineage, consistency repair, and action-level governance.

## Final Stop Line

Backend feature work is complete after TG-221-240. Future work should be bug fixes or README, ADR, diagram, demo, and interview polish only.
