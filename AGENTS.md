# server (Java Spring Boot BFF)

Java 21 / Spring Boot 3.5.3 BFF. OAuth2 Resource Server (JWT from Keycloak). gRPC
client to Go `lottery-stats-server`. HTTP proxy to Python `agent`. Owns `app` DB schema.

## Build / test / proto

```bash
./gradlew build           # build (no gradlew in this dir — use root Makefile in Docker)
./gradlew test            # tests
./gradlew generateProto   # regenerate Java stubs from ../proto/lottery.proto
./gradlew bootRun         # run locally
```

No `gradlew` wrapper in this directory — the root orchestrator runs Gradle inside
Docker. For local runs, install Gradle or use `make shell-server` then `./gradlew`.

Proto source: `../proto/lottery.proto` (shared at orchestrator root, NOT in this dir).
Build config: `build.gradle.kts` lines 43-50. Orchestrator's `make proto-java` runs
`./gradlew generateProto` in the container.

## Package layout (`src/main/java/com/statistiloto/server/`)

- `controller/` — REST endpoints (thin: extract JWT, log, delegate to service).
  - `AgentController` (`/api/agent`) — proxy to Python agent + admin endpoints.
  - `GenerateController` (`/api/generate`) — proxy to Go via gRPC.
  - `UserController` (`/api`) — `/api/me`, `/api/auth/verify` (Traefik ForwardAuth).
  - `UserNumbersController` (`/api/user/numbers`) — saved numbers CRUD.
- `service/` — business logic + external clients.
  - `LotteryClientService` — gRPC calls to Go (generateForm, getStatistics, analyze).
  - `AgentClientService` — HTTP proxy to Python agent (5-min read timeout).
  - `SavedNumbersService`, `UserProfileService` — `@Transactional` DB ops.
- `repository/` — Spring Data JPA repos (`SavedNumbersRepository`, `UserProfileRepository`).
- `entity/` — JPA entities (`SavedNumbers`, `UserProfile`).
- `dto/request/` — request DTOs with validation (`*Request`).
- `dto/response/` — response DTOs as Java records (`*Response`).
- `security/` — `SecurityConfig` (OAuth2 Resource Server, JWT, role mapping).
- `grpc/` — `GrpcClientConfig` (shared ManagedChannel, blocking stub, plaintext).
- `exception/` — `GlobalExceptionHandler`, `ErrorResponse`, `RequestLoggingFilter`.

## REST endpoints

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| GET  | `/api/me` | USER | returns sub, email, name, roles |
| GET  | `/api/auth/verify` | public | Traefik ForwardAuth target |
| GET  | `/api/user/numbers` | USER | list own saved numbers |
| POST | `/api/user/numbers` | USER | save numbers (auto-creates profile) |
| DELETE | `/api/user/numbers/{id}` | USER | ownership-checked delete |
| POST | `/api/generate/form` | USER | → gRPC GenerateForm |
| POST | `/api/generate/statistics` | USER | → gRPC GetStatistics |
| POST | `/api/generate/analyze` | USER | → gRPC Analyze |
| POST | `/api/agent/chat` | USER | → HTTP to Python agent |
| POST | `/api/agent/approve` | USER | → HTTP to Python agent (HITL) |
| GET  | `/api/agent/health` | USER | agent health |
| GET  | `/api/agent/llm-config` | ADMIN | |
| PUT  | `/api/agent/llm-config` | ADMIN | |
| GET  | `/api/agent/token-usage` | ADMIN | |
| GET  | `/api/agent/audit-log` | ADMIN | |

Public: `/api/auth/verify`, `/actuator/health`, `/actuator/info`, Swagger UI.
All other `/api/**` require auth.

## Security

- OAuth2 Resource Server, JWT via Keycloak JWKS (`KEYCLOAK_JWKS_URL`).
- Stateless sessions, CSRF disabled.
- Audience must include `statistiloto-ui` (application.yml).
- Role mapping: `realm_access.roles` AND `groups` claims → `ROLE_<NAME>`.
- Method security via `@EnableMethodSecurity` → `@PreAuthorize("hasRole('ADMIN')")`.
- JWT subject (`jwt.getSubject()`) is the user identity for all data access.
- Ownership checks in service layer (e.g. SavedNumbersService.delete).

## Database

- Schema: `app`. Tables: `user_profile` (PK `sub`), `saved_numbers` (FK → user_profile).
- Flyway migrations: `src/main/resources/db/migration/` (currently `V1__create_app_schema.sql`).
- Hibernate `ddl-auto: validate` — schema changes MUST go through Flyway, never auto-DDL.
- `UserProfileService.ensureProfile()` auto-creates profile on first login / before save
  (satisfies FK constraint).

## Config (env vars)

`SERVER_HTTP_PORT`, `SERVER_PROFILE`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`,
`DB_PASSWORD`, `KEYCLOAK_JWKS_URL`, `LOTTERY_GRPC_HOST`, `LOTTERY_GRPC_PORT`,
`AGENT_SERVICE_URL`, `AGENT_READ_TIMEOUT_MS` (default 300000 = 5 min for LLM).

Config file: `src/main/resources/application.yml`. No profile-specific yml files.

## Conventions

- Request DTOs: `*Request` in `dto/request/`. Response DTOs: `*Response` records in `dto/response/`.
- Agent DTOs use `@JsonProperty("snake_case")` + `@JsonAlias("camelCase")` for Python API compat.
- Structured logging: `[methodName] START/SUCCESS/ERROR` with context.
- `GlobalExceptionHandler` maps exceptions → `ErrorResponse` (error, message, status, timestamp, path).
  gRPC errors mapped to HTTP status codes; validation errors → 400 with field details.
  4xx logged WARN, 5xx logged ERROR with stack trace.

## Gotchas

- Proto source is at `../proto/` (orchestrator root), not in this dir. Dockerfile build
  context must be the orchestrator root: `docker build -f server/Dockerfile -t ... .`
  from repo root, NOT from `server/`.
- gRPC uses `usePlaintext()` — assumes traffic stays in Docker network. Not for external gRPC.
- `LotteryClientService` uses fully-qualified proto DTO names to avoid collision with
  local DTOs of the same name — keep that pattern when adding new gRPC calls.
- No `gradlew` in this dir. Use root Makefile (`make test-java`, `make proto-java`) or
  run inside container via `make shell-server`.
- Agent read timeout is 5 min (LLM inference can be slow). Don't reduce without reason.
- `/api/auth/verify` is "public" but Spring Security filter chain still validates JWT
  before the controller — it's the Traefik ForwardAuth probe, not a true anonymous endpoint.
