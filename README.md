# Statistiloto Server (BFF)

Java Spring Boot BFF — OAuth2 Resource Server, REST API for the UI, gRPC client to the Go lottery service, owns the `app` DB schema.

## Tech Stack

| Component | Version / Library |
|---|---|
| Framework | Spring Boot 3.5.3 |
| Language | Java 21 |
| Build | Gradle Kotlin DSL |
| Security | Spring Security OAuth2 Resource Server (JWT) |
| ORM | Spring Data JPA / Hibernate |
| Migrations | Flyway |
| Database | PostgreSQL (shared, `app` schema) |
| gRPC | grpc-netty-shaded 1.68.2, protobuf 3.25.5 |
| API Docs | springdoc-openapi 2.6.0 (Swagger UI) |
| Boilerplate | Lombok |
| Tests | JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL), MockWebServer |

## Architecture

```mermaid
graph LR
    UI[Angular UI<br/>PWA] -->|HTTPS| TR[Traefik<br/>Reverse Proxy]
    TR -->|ForwardAuth| BFF_V[/api/auth/verify<br/>JWT check/]
    TR -->|HTTP :8082| BFF[Java BFF<br/>Spring Boot]
    BFF -->|gRPC :9090| GO[Go lottery-stats-server<br/>algorithm engine]
    BFF -->|HTTP :8000| AGENT[Python Agent Service<br/>LangGraph worker]
    BFF -->|JDBC :5432| DB[(PostgreSQL<br/>app schema)]
    KC[Keycloak<br/>OIDC IdP] -.->|JWKS| BFF
    KC -.->|JWKS| TR
```

The Angular UI talks **only** to the BFF. The BFF:

1. Validates the user's Keycloak-issued JWT on every request.
2. Proxies lottery computation requests to the Go service via gRPC.
3. Proxies agent chat/approve requests to the Python agent service via HTTP.
4. Reads and writes application data (user profiles, saved numbers) in the `app` schema of the shared PostgreSQL database.

## API Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/generate/form` | JWT | Generate lottery number combinations (proxied to Go via gRPC `GenerateForm`) |
| POST | `/api/generate/statistics` | JWT | Calculate frequent number pairs/groups (proxied to Go via gRPC `GetStatistics`) |
| POST | `/api/generate/analyze` | JWT | Analyze user-selected numbers against historical draws (proxied to Go via gRPC `Analyze`) |
| GET | `/api/user/numbers` | JWT | List the authenticated user's saved lottery numbers |
| POST | `/api/user/numbers` | JWT | Save a new set of lottery numbers for the authenticated user |
| DELETE | `/api/user/numbers/{id}` | JWT | Delete a saved numbers entry (ownership-checked) |
| GET | `/api/me` | JWT | Return the authenticated user's profile (sub, email, name, roles); auto-creates `user_profile` row on first login |
| POST | `/api/agent/chat` | JWT | Proxy a chat request to the Python agent service (forwards JWT Bearer token) |
| POST | `/api/agent/approve` | JWT | Proxy an approval decision to the Python agent service |
| GET | `/api/agent/llm-config` | JWT + ADMIN | Get the agent's LLM configuration |
| PUT | `/api/agent/llm-config` | JWT + ADMIN | Update the agent's LLM configuration |
| GET | `/api/agent/health` | JWT | Check agent service health |
| GET | `/api/agent/token-usage` | JWT + ADMIN | Get agent token usage statistics |
| GET | `/api/agent/audit-log` | JWT + ADMIN | Get agent audit log |
| GET | `/api/auth/verify` | Public | ForwardAuth endpoint for Traefik edge JWT validation; returns 200 if valid |
| GET | `/actuator/health` | Public | Spring Boot Actuator health check |
| GET | `/actuator/info` | Public | Application info |
| GET | `/swagger-ui.html` | Public | Swagger UI |
| GET | `/api-docs` | Public | OpenAPI spec |

## Project Structure

```
server/
├── build.gradle.kts              # Gradle build (Spring Boot 3.5.3, Java 21, gRPC, Flyway)
├── settings.gradle.kts
├── Dockerfile                    # Multi-stage build (Gradle → eclipse-temurin:21-jre-alpine)
├── docs/
│   ├── REQUIREMENTS.md           # Functional and non-functional requirements
│   └── FLOWS.md                  # Mermaid sequence diagrams for key flows
└── src/
    ├── main/
    │   ├── java/com/statistiloto/server/
    │   │   ├── ServerApplication.java          # Spring Boot entry point
    │   │   ├── controller/
    │   │   │   ├── AgentController.java         # /api/agent/* — proxy to Python agent
    │   │   │   ├── GenerateController.java      # /api/generate/* — proxy to Go via gRPC
    │   │   │   ├── UserController.java          # /api/me, /api/auth/verify
    │   │   │   └── UserNumbersController.java   # /api/user/numbers CRUD
    │   │   ├── service/
    │   │   │   ├── AgentClientService.java      # HTTP client to Python agent service
    │   │   │   ├── LotteryClientService.java     # gRPC client to Go lottery service
    │   │   │   ├── SavedNumbersService.java      # CRUD for saved_numbers
    │   │   │   └── UserProfileService.java       # Auto-create user_profile on first login
    │   │   ├── security/
    │   │   │   └── SecurityConfig.java           # OAuth2 Resource Server, stateless, role mapping
    │   │   ├── grpc/
    │   │   │   └── GrpcClientConfig.java         # ManagedChannel + blocking stub for Go service
    │   │   ├── entity/
    │   │   │   ├── SavedNumbers.java             # JPA entity (app.saved_numbers)
    │   │   │   └── UserProfile.java              # JPA entity (app.user_profile)
    │   │   ├── repository/
    │   │   │   ├── SavedNumbersRepository.java   # Spring Data JPA repository
    │   │   │   └── UserProfileRepository.java    # Spring Data JPA repository
    │   │   ├── dto/
    │   │   │   ├── request/                      # Inbound request DTOs (validated)
    │   │   │   └── response/                     # Outbound response DTOs (records)
    │   │   └── exception/
    │   │       ├── ErrorResponse.java            # Structured error body
    │   │       ├── GlobalExceptionHandler.java   # @RestControllerAdvice, maps gRPC/valid/auth errors
    │   │       └── RequestLoggingFilter.java     # Request logging filter
    │   └── resources/
    │       ├── application.yml                   # Configuration (env-driven)
    │       └── db/migration/
    │           └── V1__create_app_schema.sql     # Flyway: creates app.user_profile + app.saved_numbers
    └── test/
        └── java/com/statistiloto/server/
            ├── ServerApplicationTests.java       # Context load test
            ├── controller/
            │   └── AgentControllerTest.java       # Agent controller tests
            └── service/
                └── AgentClientServiceTest.java    # Agent client service tests
```

## Quick Start

### Prerequisites

- Java 21 (JDK)
- Gradle 8.10+ (or use the included wrapper `./gradlew`)
- PostgreSQL (or run via Docker Compose from the repo root)
- Keycloak (for JWT issuance)
- Go lottery-stats-server (for gRPC endpoints)
- Python agent service (for agent endpoints)

### Build

```bash
./gradlew build
```

### Run (development)

```bash
./gradlew bootRun
```

The server starts on port `8082` by default (configurable via `SERVER_HTTP_PORT`).

### Test

```bash
./gradlew test
```

Tests use H2 for unit tests and Testcontainers (PostgreSQL) for integration tests. MockWebServer is used to mock the agent service.

## Environment Configuration

All configuration is environment-variable driven with sensible defaults for local development. See `src/main/resources/application.yml`.

| Environment Variable | Default | Description |
|---|---|---|
| `SERVER_HTTP_PORT` | `8082` | HTTP port the BFF listens on |
| `SERVER_PROFILE` | `dev` | Spring profile |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `statistiloto` | PostgreSQL database name |
| `DB_USER` | `postgres` | PostgreSQL username |
| `DB_PASSWORD` | `postgres` | PostgreSQL password |
| `KEYCLOAK_JWKS_URL` | `http://auth:8080/auth/realms/statistiloto/protocol/openid-connect/certs` | Keycloak JWKS URI for JWT signature validation |
| `LOTTERY_GRPC_HOST` | `localhost` | Go lottery-stats-server gRPC host |
| `LOTTERY_GRPC_PORT` | `9090` | Go lottery-stats-server gRPC port |
| `AGENT_SERVICE_URL` | `http://agent:8000` | Python agent service base URL |
| `AGENT_READ_TIMEOUT_MS` | `300000` | Agent service HTTP read timeout (5 min default for LLM inference) |

## Security

### OAuth2 Resource Server

The BFF is a **stateless** OAuth2 Resource Server. It validates Keycloak-issued JWTs (RS256) on every request.

- **JWKS validation**: Spring Security fetches public keys from the `KEYCLOAK_JWKS_URL` to verify JWT signatures. Using `jwk-set-uri` (instead of `issuer-uri`) avoids issuer mismatch when the internal container URL differs from the public URL.
- **Audience validation**: The Keycloak realm includes an `oidc-audience-mapper` on the `statistiloto-ui` client that adds `statistiloto-ui` to the `aud` claim of every access token. The BFF rejects tokens that don't contain this audience.
- **Session**: `SessionCreationPolicy.STATELESS` — no server-side sessions, no CSRF.
- **Role mapping**: Realm roles from `realm_access.roles` and groups from the `groups` claim are mapped to `ROLE_<NAME>` Spring Security authorities, enabling `@PreAuthorize("hasRole('ADMIN')")` on admin-only endpoints.

### Public Endpoints

The following endpoints are accessible without authentication:

- `/api/auth/verify` — Traefik ForwardAuth endpoint
- `/actuator/health`, `/actuator/info` — health checks
- `/swagger-ui/**`, `/api-docs`, `/v3/api-docs/**` — API documentation

All other `/api/**` endpoints require a valid JWT.

### ForwardAuth

Traefik's ForwardAuth middleware calls `/api/auth/verify` before forwarding requests to the BFF. If the JWT is valid, Spring Security has already authenticated the request and the endpoint returns `{ "authenticated": true, "sub": "...", "email": "..." }` with HTTP 200. If invalid, Spring Security returns 401 and Traefik blocks the request.

## Database

### Schema Ownership

The BFF owns the **`app`** schema in the shared PostgreSQL database. Keycloak owns identity (users, credentials, roles). The BFF stores only application data keyed by the Keycloak `sub` (subject) claim — no passwords, no user credentials.

### Flyway Migrations

Migrations are in `src/main/resources/db/migration/` and are applied automatically on startup. Flyway is configured with:

- `schemas: app`
- `default-schema: app`
- `locations: classpath:db/migration`

Hibernate is set to `ddl-auto: validate` — it validates the entity mappings against the Flyway-managed schema but never modifies it.

### Tables

#### `app.user_profile`

| Column | Type | Notes |
|---|---|---|
| `sub` | TEXT | Primary key — Keycloak subject claim |
| `display_name` | VARCHAR(255) | Display name from JWT `name` or `preferred_username` |
| `created_at` | TIMESTAMP | Row creation timestamp |
| `updated_at` | TIMESTAMP | Row update timestamp |

Auto-created on first login via `/api/me` and before any `saved_numbers` insert (to satisfy the FK constraint).

#### `app.saved_numbers`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGSERIAL | Primary key |
| `user_sub` | TEXT | FK → `app.user_profile(sub)` ON DELETE CASCADE |
| `category` | VARCHAR(50) | User-defined category label |
| `numbers` | JSONB | Array of lottery numbers |
| `will_be` | JSONB | Optional "will be" (strong) numbers |
| `date_from` | DATE | Optional date window start |
| `date_to` | DATE | Optional date window end |
| `created_at` | TIMESTAMP | Row creation timestamp |

Indexes: `idx_saved_numbers_user_sub`, `idx_saved_numbers_category`.

## gRPC Client

The BFF connects to the Go `lottery-stats-server` via gRPC using a shared `ManagedChannel` configured in `GrpcClientConfig.java`.

- **Channel**: `ManagedChannelBuilder.forAddress(host, port).usePlaintext().build()` — plaintext is used because traffic stays within the Docker network.
- **Stub**: `LotteryServiceGrpc.LotteryServiceBlockingStub` — blocking calls for synchronous request/response.
- **Proto contract**: `proto/lottery.proto` at the repository root (shared between Java and Go). The protobuf Gradle plugin generates Java stubs from this file.
- **Lifecycle**: The channel is shut down gracefully on application stop (`@PreDestroy`, 5-second await).

### gRPC Methods

| BFF Endpoint | gRPC RPC | Proto Request | Proto Response |
|---|---|---|---|
| `POST /api/generate/form` | `GenerateForm` | `GenerateFormRequest` | `GenerateFormResponse` (list of `NumberSet`) |
| `POST /api/generate/statistics` | `GetStatistics` | `GetStatisticsRequest` | `GetStatisticsResponse` (list of `Pair`) |
| `POST /api/generate/analyze` | `Analyze` | `AnalyzeRequest` | `AnalyzeResponse` (list of `FrequencyGroup` + `archive_size`) |

gRPC errors are mapped to HTTP status codes by `GlobalExceptionHandler` (e.g., `INVALID_ARGUMENT` → 400, `NOT_FOUND` → 404, `UNAVAILABLE` → 503).

## Agent Proxy

The BFF proxies requests to the Python agent service (LangGraph worker) via HTTP. The UI never calls the agent service directly.

- **Client**: `AgentClientService` uses Spring's `RestClient` with a configurable read timeout (default 5 minutes for LLM inference).
- **Auth forwarding**: The user's JWT Bearer token is forwarded to the agent service in the `Authorization` header.
- **Endpoints**: `/chat`, `/approve`, `/llm-config` (GET/PUT), `/health`, `/token-usage`, `/audit-log`.
- **Admin-only**: LLM config, token usage, and audit log endpoints require `ROLE_ADMIN`.

## Docker

The `Dockerfile` uses a multi-stage build:

### Build Stage

- Base: `gradle:8.10.2-jdk21`
- Copies `build.gradle.kts` and `settings.gradle.kts` first for dependency caching.
- Copies the shared `proto/` directory (needed for gRPC stub codegen).
- Warms the Gradle dependency cache with `--mount=type=cache`.
- Builds the fat jar with `gradle bootJar -x test`.
- Extracts Spring Boot layers for optimized runtime image caching.

### Runtime Stage

- Base: `eclipse-temurin:21-jre-alpine`
- Runs as non-root user `spring`.
- Copies extracted Spring Boot layers (dependencies → snapshot-dependencies → application).
- Exposes port `8082`.
- Healthcheck: `wget -qO- http://localhost:8082/actuator/health` (30s interval, 40s start period).

### Build Command

```bash
# From the repository root (build context is repo root):
docker build -f server/Dockerfile -t statistiloto-server .
```

## Testing

```bash
./gradlew test
```

| Test File | Scope |
|---|---|
| `ServerApplicationTests.java` | Spring context load test |
| `AgentControllerTest.java` | Agent controller endpoint tests |
| `AgentClientServiceTest.java` | Agent client service tests (MockWebServer) |

Tests use:
- **H2** — in-memory database for fast unit tests
- **Testcontainers (PostgreSQL)** — real PostgreSQL for integration tests
- **MockWebServer** (OkHttp) — mock the Python agent service HTTP responses
- **Spring Security Test** — mock JWT authentication

## Documentation

- [Requirements](docs/REQUIREMENTS.md) — functional and non-functional requirements
- [Flows](docs/FLOWS.md) — Mermaid sequence diagrams for key request flows
