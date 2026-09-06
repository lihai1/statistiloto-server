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
  - `AdminArchiveController` (`/api/admin/archived-users`) — admin-only audit of archived user profiles.
  - `FeedbackController` (`/api/feedback`) — user feedback & admin management.
  - `GenerateController` (`/api/generate`) — proxy to Go via gRPC.
  - `SavedSimulationController` (`/api/user/simulations`) — saved simulation CRUD.
  - `UserController` (`/api`) — `/api/me`, `/api/me/archive`, `DELETE /api/me` (soft-archive), `/api/auth/verify`.
  - `UserNumbersController` (`/api/user/numbers`) — saved numbers CRUD.
- `service/` — business logic + external clients.
  - `AgentClientService` — HTTP proxy to Python agent (5-min read timeout, SSE + Redis relay).
  - `FeedbackService` — CRUD for `app.feedback`.
  - `LotteryClientService` — gRPC calls to Go (generateForm, getStatistics, analyze, simulate).
  - `SavedNumbersService` — CRUD for `app.saved_numbers` with duplicate validation.
  - `SavedSimulationService` — CRUD for `app.saved_simulations`.
  - `UserProfileService` — auto-create/reactivate profile, archive window updates, soft-archive (`archiveUser`).
- `repository/` — Spring Data JPA repos (`FeedbackRepository`, `SavedNumbersRepository`, `SavedSimulationRepository`, `UserProfileRepository`).
- `entity/` — JPA entities (`Feedback`, `SavedNumbers`, `SavedSimulation`, `UserProfile`).
- `dto/request/` — request DTOs with validation (`*Request`).
- `dto/response/` — response DTOs as Java records (`*Response`).
- `security/` — `SecurityConfig` (OAuth2 Resource Server, JWT, role mapping).
- `grpc/` — `GrpcClientConfig` (shared ManagedChannel, blocking stub, plaintext).
- `exception/` — `GlobalExceptionHandler`, `ErrorResponse`, `RequestLoggingFilter`.

## REST endpoints

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| GET | `/api/me` | USER | returns sub, email, name, roles, archiveFrom, archiveTo |
| GET | `/api/auth/verify` | public | Traefik ForwardAuth target |
| GET | `/api/user/numbers` | USER | list own saved numbers |
| POST | `/api/user/numbers` | USER | save numbers (auto-creates profile) |
| DELETE | `/api/user/numbers/{id}` | USER | ownership-checked delete |
| POST | `/api/generate/form` | USER | → gRPC GenerateForm |
| POST | `/api/generate/statistics` | USER | → gRPC GetStatistics |
| POST | `/api/generate/analyze` | USER | → gRPC Analyze |
| POST | `/api/generate/simulate` | USER | → gRPC Simulate (backtest) |
| POST | `/api/agent/chat` | USER | → HTTP to Python agent (optional `config_id`, `lang`) |
| POST | `/api/agent/approve` | USER | → HTTP to Python agent (HITL) |
| GET | `/api/agent/health` | USER | agent health |
| GET | `/api/agent/sessions` | USER | list caller's sessions |
| GET | `/api/agent/sessions/{sessionId}` | USER | get one session |
| DELETE | `/api/agent/sessions/{sessionId}` | USER | delete one session |
| DELETE | `/api/agent/sessions` | USER | delete all caller's sessions |
| GET | `/api/agent/llm-config` | ADMIN | active LLM config |
| PUT | `/api/agent/llm-config` | ADMIN | update active LLM config |
| GET | `/api/agent/llm-configs` | ADMIN | list stored configs |
| POST | `/api/agent/llm-configs` | ADMIN | create stored config |
| PUT | `/api/agent/llm-configs/{configId}` | ADMIN | update a stored config |
| PUT | `/api/agent/llm-configs/{configId}/activate` | ADMIN | activate a stored config |
| POST | `/api/agent/llm-configs/{configId}/test` | ADMIN | smoke-test a stored config |
| DELETE | `/api/agent/llm-configs/{configId}` | ADMIN | delete a stored config |
| GET | `/api/agent/llm-models?provider=...&base_url=...` | ADMIN | list models from a provider (optional `base_url`) |
| GET | `/api/agent/free-llm` | ADMIN | read the free-tier LLM toggle |
| PUT | `/api/agent/free-llm` | ADMIN | set the free-tier LLM toggle |
| GET | `/api/agent/token-usage` | ADMIN | token usage stats |
| GET | `/api/agent/audit-log?limit=50` | ADMIN | audit log (optional limit) |
| POST | `/api/agent/reindex` | ADMIN | rebuild pgvector RAG embeddings |
| PUT | `/api/me/archive` | USER | update preferred archive date range |
| DELETE | `/api/me` | USER | soft-archive account (sets archived_at on profile + child tables; re-login reactivates) |
| GET | `/api/admin/archived-users` | ADMIN | list all archived user profiles |
| GET | `/api/admin/archived-users/{sub}` | ADMIN | get details of a specific archived user |
| POST | `/api/feedback` | USER | submit feedback or lottery suggestion |
| GET | `/api/feedback` | ADMIN | list all feedback |
| PUT | `/api/feedback/{id}/status` | ADMIN | update feedback status |
| DELETE | `/api/feedback/{id}` | ADMIN | delete feedback |
| GET | `/api/user/simulations` | USER | list own saved simulation results |
| POST | `/api/user/simulations` | USER | save a simulation result |
| DELETE | `/api/user/simulations/{id}` | USER | delete own saved simulation result |
| POST | `/api/agent/chat/stream` | USER | stream agent chat via SSE (Redis/inline) |

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

- Schema: `app`. Tables: `user_profile` (PK `sub`, plus `archive_from`/`archive_to`/`archived_at`), `saved_numbers` (+ `archived_at`), `saved_simulations` (FK → user_profile, + `archived_at`), `feedback` (+ `archived_at`).
- Flyway migrations: `src/main/resources/db/migration/` (`V1__create_app_schema.sql`, `V2__add_archive_window_to_user_profile.sql`, `V3__create_saved_simulations.sql`, `V4__create_feedback.sql`, `V5__add_archived_at.sql`).
- Hibernate `ddl-auto: validate` — schema changes MUST go through Flyway, never auto-DDL.
- `UserProfileService.ensureProfile()` auto-creates profile on first login / before save
  (satisfies FK constraint). On re-login after soft-archive, it reactivates the profile
  with fresh defaults (clears `archived_at`, `archive_from`, `archive_to`); old child
  records stay archived.

## Config (env vars)

`SERVER_HTTP_PORT`, `SERVER_PROFILE`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`,
`DB_PASSWORD`, `KEYCLOAK_JWKS_URL`, `LOTTERY_GRPC_HOST`, `LOTTERY_GRPC_PORT`,
`AGENT_SERVICE_URL`, `AGENT_READ_TIMEOUT_MS` (default 300000 = 5 min for LLM),
`REDIS_URL` (Redis for agent SSE pub/sub relay; optional).

Config file: `src/main/resources/application.yml`. No profile-specific yml files.

## Conventions

- Request DTOs: `*Request` in `dto/request/`. Response DTOs: `*Response` records in `dto/response/`.
- Agent DTOs use `@JsonProperty("snake_case")` + `@JsonAlias("camelCase")` for Python API compat.
- Structured logging: `[methodName] START/SUCCESS/ERROR` with context.
- `GlobalExceptionHandler` maps exceptions → `ErrorResponse` (error, message, status, timestamp, path).
  gRPC errors mapped to HTTP status codes; validation errors → 400 with field details.
  Upstream agent HTTP errors (`HttpClientErrorException` / `HttpServerErrorException`) are
  propagated with the upstream status code and body as `UPSTREAM_ERROR`. 4xx logged WARN, 5xx
  logged ERROR with stack trace.

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
