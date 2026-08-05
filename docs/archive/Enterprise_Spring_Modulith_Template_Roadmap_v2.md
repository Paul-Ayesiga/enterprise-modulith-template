# Enterprise Spring Modulith Template Roadmap

> **ARCHIVED.** The original vision document. Superseded by
> [../IMPLEMENTATION_PLAN.md](../plans/IMPLEMENTATION_PLAN.md) (which pins what was actually built) and by
> the shipped code — the group id (`io.commuza` → `ug.co.smsone`), the module list and several
> library choices changed during implementation. Kept as written.

## Purpose
This document is the master guide for building an opinionated, enterprise-grade Spring Boot + Spring Modulith template. It is organized into phases so each capability is built once and reused across future applications.

# Vision
- Zero infrastructure duplication
- Domain-first design
- Event-driven modules
- Production-ready defaults
- Cloud-native
- Modular monolith with future microservice extraction path

# Core Principles
- Domain-first design
- Modules own their data
- Modules communicate through events
- Constructor injection only
- No cyclic dependencies
- Hexagonal architecture inside each module
- Infrastructure behind interfaces
- Testability by default

# Recommended Project Metadata
- Name: enterprise-modulith-template
- Java: 21
- Build: Gradle Kotlin DSL
- Packaging: Jar
- Configuration: YAML
- Group: io.commuza (or your company)
- Artifact: enterprise-modulith-template

# Proposed Project Structure

```text
enterprise-modulith-template/
├── application/
├── shared/
│   ├── security/
│   ├── persistence/
│   ├── auditing/
│   ├── events/
│   ├── validation/
│   ├── configuration/
│   ├── logging/
│   ├── observability/
│   ├── exception/
│   ├── utilities/
│   └── testing/
├── modules/
│   ├── identity/
│   ├── organization/
│   ├── notification/
│   ├── files/
│   ├── scheduler/
│   ├── settings/
│   ├── localization/
│   ├── search/
│   ├── document/
│   ├── audit/
│   └── health/
├── docs/
├── docker/
├── scripts/
└── build.gradle.kts
```

# Development Phases

## Phase 0 – Foundation
- Spring Boot
- Spring Modulith
- Java 21
- Gradle Kotlin DSL
- YAML
- Docker
- README
- ADR structure
- CI skeleton
- Modulith documentation generation

## Phase 1 – Shared Infrastructure
### Security
- OAuth2
- JWT
- Keycloak
- CurrentUser abstraction
- Method security
- Permission evaluator

### Persistence
- PostgreSQL
- Flyway
- BaseEntity
- AggregateRoot
- UUID keys
- Auditing
- Soft delete
- Optimistic locking
- Specifications
- Pagination

### Configuration
- Jackson
- Clock
- Locale
- TimeZone
- OpenAPI
- Async
- Scheduling

### Validation
- Email
- Phone
- Password strength
- Money
- Currency
- UUID
- Date ranges

### Exception Handling
- BusinessException
- ValidationException
- ConflictException
- ForbiddenException
- UnauthorizedException
- NotFoundException
- Global exception handler

### Logging
- Structured JSON
- Correlation ID
- Request ID
- User ID
- Organization ID
- Execution timing

### Observability
- Micrometer
- Prometheus
- OpenTelemetry
- Health indicators
- Metrics
- Distributed tracing

### Testing
- JUnit 5
- Testcontainers
- PostgreSQL
- MockMvc
- Integration test base
- Module tests
- ArchUnit

## Phase 2 – Core Business Modules
### Identity
Users, Roles, Permissions, Groups, Profiles, Passwords
Events:
- UserCreated
- UserUpdated
- UserDisabled
- PasswordChanged

### Organization
Organizations, Departments, Teams, Membership

### Notification
Email, SMS, Push, Slack, Teams, Webhooks

### Files
Uploads, Downloads, Metadata, Virus Scan, Image Resize
Providers:
- Local
- MinIO
- Amazon S3
- Azure Blob
- Google Cloud Storage

### Scheduler
Cron, Retry, Distributed Locks, Job History

### Search
Filtering, Specifications, Sorting, Pagination, Full-text abstraction

### Document
PDF, Excel, Word, CSV, HTML exports

### Audit
Who, What, When, Old/New Value, IP, Device

### Settings
System, Organization and User settings, Feature toggles

### Localization
Languages, Timezones, Formats, Bundles

### Health
Readiness, Liveness, Build info, Git Commit, Version

## Phase 3 – Enterprise Capabilities
- Feature Flags
- Outbox Pattern
- Inbox Pattern
- Idempotency
- Multi-tenancy
- Caching (Caffeine/Redis)
- RBAC
- ABAC extension
- API versioning
- RFC 9457 Problem Details

## Phase 4 – Documentation
- Spring Modulith diagrams
- Dependency graph
- Event catalog
- Architecture Decision Records
- PlantUML
- Mermaid
- C4
- OpenAPI

## Phase 5 – Future Modules
- Workflow Engine
- Billing & Payments
- Messaging (Kafka/RabbitMQ)
- Identity Federation (LDAP/SAML/OIDC)
- AI Services
- Reporting
- Search Indexing
- Secrets Management
- Background Workers
- Integration Hub
- Media Processing
- Import/Export Framework
- Compliance
- Rate Limiting
- License Management
- Plugin Framework
- Event Store
- Disaster Recovery

# Recommended Dependencies
- Spring Boot
- Spring Modulith
- Spring Security
- Spring Data JPA
- Spring Validation
- Spring Actuator
- PostgreSQL
- Flyway
- Testcontainers
- Micrometer
- OpenTelemetry
- SpringDoc OpenAPI
- MapStruct
- ArchUnit
- Caffeine
- Spring Cache

# Long-term Goal
Every new application should primarily add business modules while reusing this shared enterprise foundation.


## Phase 3A – Analytics Platform (DuckDB)

### Vision
Use PostgreSQL as the transactional database (OLTP) and DuckDB as the embedded analytics engine (OLAP). Business modules always write to PostgreSQL. Heavy reporting and analytical workloads are executed through the analytics module.

### Architecture
- PostgreSQL remains the system of record.
- DuckDB performs dashboards, KPIs, reporting and aggregations.
- Prefer an AnalyticsEngine abstraction to allow future engines (DuckDB, ClickHouse, Trino, PostgreSQL fallback).

### Analytics Module
Responsibilities:
- AnalyticsQueryService
- DashboardService
- ReportService
- KPIService
- DataExportService
- SnapshotService

### DuckDB Capabilities
- PostgreSQL integration
- Parquet
- CSV
- JSON
- Apache Arrow
- Window functions
- Time-series analytics
- Pivot-style reporting

### Materialization Strategy
- Live PostgreSQL scans where appropriate.
- Scheduled Parquet snapshots for expensive reports.
- Incremental refresh.
- Cache warming.

### Enterprise Reporting
- Financial Statements
- Occupancy Dashboards
- Revenue Analytics
- Collection Analytics
- Aging Reports
- Executive KPIs
- Trend Analysis
- Forecasting

### Future Analytics Engines
- DuckDB (default)
- PostgreSQL (fallback)
- ClickHouse
- Trino

