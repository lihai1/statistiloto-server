# Requirements

## Functional Requirements

### Lottery Generation (gRPC → Go)

| ID | Requirement |
|---|---|
| FR-1 | The BFF SHALL expose `POST /api/generate/form` to generate lottery number combinations based on historical-draw patterns. The request accepts `howMany`, `formType`, `willBe` (required numbers), `strength` (strong/weak), and an optional date window (`from`/`to`). The BFF proxies this to the Go service via gRPC `GenerateForm`. |
| FR-2 | The BFF SHALL expose `POST /api/generate/statistics` to calculate frequent number pairs/groups. The request accepts `howMany`, `formType`, `strength`, and an optional date window. The BFF proxies this to the Go service via gRPC `GetStatistics`. |
| FR-3 | The BFF SHALL expose `POST /api/generate/analyze` to evaluate user-selected numbers against historical winning draws. The request accepts a `form` (list of numbers) and an optional date window. The BFF proxies this to the Go service via gRPC `Analyze`. The response includes frequency groups (by group size 1–6) and the archive size. |
| FR-3a | The BFF SHALL expose `POST /api/generate/simulate` to backtest a user's ticket against historical draws. The request accepts `form` (6/8/10/12 numbers for systematic forms), `strong` (optional), `from`/`to` (optional date window), `ticketCost` (optional, default 3.0), and `prizeAmounts` (optional length-0-or-8 array of per-tier ILS overrides). The BFF proxies this to the Go service via gRPC `Simulate`. The response includes per-draw results (`draws`) and an aggregated `summary` (total draws, spend, winnings, net, per-tier totals, draws priced with real scraped prizes). |

### User Profile

| ID | Requirement |
|---|---|
| FR-4 | The BFF SHALL expose `GET /api/me` to return the authenticated user's profile (`sub`, `email`, `name`, `roles`) extracted from the JWT claims. |
| FR-5 | The BFF SHALL auto-create a `user_profile` row in the `app` schema on first login (when `/api/me` is called and no row exists for the user's `sub`). |
| FR-6 | The BFF SHALL extract realm roles from the `realm_access.roles` JWT claim and return them in the `/api/me` response. |

### Saved Numbers CRUD

| ID | Requirement |
|---|---|
| FR-7 | The BFF SHALL expose `GET /api/user/numbers` to list all saved lottery numbers for the authenticated user, ordered by `created_at` descending. |
| FR-8 | The BFF SHALL expose `POST /api/user/numbers` to save a new set of lottery numbers. The request accepts `category`, `numbers` (required), `willBe` (optional), `dateFrom` (optional), and `dateTo` (optional). The `user_sub` is taken from the JWT, not the request body. |
| FR-9 | The BFF SHALL expose `DELETE /api/user/numbers/{id}` to delete a saved numbers entry. The BFF SHALL verify that the entry belongs to the authenticated user before deleting; if the user does not own the entry, the BFF returns 403. |
| FR-10 | The BFF SHALL ensure a `user_profile` row exists before inserting into `saved_numbers` to satisfy the foreign key constraint. |

### Agent Proxy

| ID | Requirement |
|---|---|
| FR-11 | The BFF SHALL expose `POST /api/agent/chat` to proxy chat requests to the Python agent service. The user's JWT Bearer token SHALL be forwarded in the `Authorization` header. The request accepts optional `config_id` (per-request LLM override with a stored config) and `lang` (language hint forwarded to workers). |
| FR-12 | The BFF SHALL expose `POST /api/agent/approve` to proxy approval decisions to the Python agent service. The user's JWT Bearer token SHALL be forwarded. |
| FR-13 | The BFF SHALL expose `GET /api/agent/llm-config` (admin-only) to retrieve the agent's active LLM configuration. |
| FR-14 | The BFF SHALL expose `PUT /api/agent/llm-config` (admin-only) to update the agent's active LLM configuration. |
| FR-15 | The BFF SHALL expose `GET /api/agent/health` to check the agent service health. |
| FR-16 | The BFF SHALL expose `GET /api/agent/token-usage` (admin-only) to retrieve agent token usage statistics. |
| FR-17 | The BFF SHALL expose `GET /api/agent/audit-log` (admin-only) to retrieve the agent audit log. An optional `limit` query parameter (default 50) SHALL control the number of entries returned. |
| FR-20 | The BFF SHALL expose `GET /api/agent/sessions` to list the authenticated user's agent sessions, `GET /api/agent/sessions/{sessionId}` to retrieve one session's history, `DELETE /api/agent/sessions/{sessionId}` to delete one session, and `DELETE /api/agent/sessions` to delete all of the caller's sessions. Sessions SHALL be scoped to the authenticated user. |
| FR-21 | The BFF SHALL expose `GET /api/agent/llm-configs` (admin-only) to list all stored LLM configurations and `POST /api/agent/llm-configs` (admin-only) to create a new stored configuration. |
| FR-22 | The BFF SHALL expose `PUT /api/agent/llm-configs/{configId}/activate` (admin-only) to activate a stored configuration, `POST /api/agent/llm-configs/{configId}/test` (admin-only) to smoke-test a stored configuration, and `DELETE /api/agent/llm-configs/{configId}` (admin-only) to delete a stored configuration. |
| FR-23 | The BFF SHALL expose `GET /api/agent/llm-models?provider=...&base_url=...` (admin-only) to list models available from a given LLM provider. The optional `base_url` query param queries a non-default provider endpoint. |
| FR-24 | The BFF SHALL expose `POST /api/agent/reindex` (admin-only) to trigger a rebuild of the agent's pgvector RAG embeddings. |
| FR-25 | The BFF SHALL expose `PUT /api/agent/llm-configs/{configId}` (admin-only) to update a stored LLM configuration (name, provider, model, base_url, api_key, timeout). |
| FR-26 | The BFF SHALL expose `GET /api/agent/free-llm` (admin-only) to read the free-tier LLM toggle (whether free users get LLM responses or a canned response). |
| FR-27 | The BFF SHALL expose `PUT /api/agent/free-llm` (admin-only) to set the free-tier LLM toggle (`{ "enabled": true }`). |

### Auth Verification

| ID | Requirement |
|---|---|
| FR-18 | The BFF SHALL expose `GET /api/auth/verify` as a public endpoint for Traefik ForwardAuth. It returns HTTP 200 with `{ authenticated: true, sub, email }` if the JWT is valid, or indicates unauthenticated if no valid JWT is present. |

### Health

| ID | Requirement |
|---|---|
| FR-19 | The BFF SHALL expose `GET /actuator/health` as a public endpoint for container orchestration and load balancer health checks. |

## Security Requirements

| ID | Requirement |
|---|---|
| SR-1 | The BFF SHALL validate every JWT using Keycloak's JWKS endpoint (`KEYCLOAK_JWKS_URL`). Tokens with invalid signatures SHALL be rejected with 401. |
| SR-2 | The BFF SHALL validate the JWT `aud` (audience) claim. Only tokens containing `statistiloto-ui` in the audience SHALL be accepted. |
| SR-3 | The BFF SHALL be stateless — `SessionCreationPolicy.STATELESS`. No server-side sessions SHALL be created. CSRF protection SHALL be disabled (no sessions, no cookies). |
| SR-4 | The BFF SHALL map Keycloak realm roles from `realm_access.roles` and groups from the `groups` claim to Spring Security `ROLE_<NAME>` authorities. |
| SR-5 | Admin-only endpoints (`/api/agent/llm-config` PUT, `/api/agent/llm-configs` GET/POST, `/api/agent/llm-configs/{configId}` PUT (update), `/api/agent/llm-configs/{configId}/activate` PUT, `/api/agent/llm-configs/{configId}/test` POST, `/api/agent/llm-configs/{configId}` DELETE, `/api/agent/llm-models`, `/api/agent/free-llm` GET/PUT, `/api/agent/token-usage`, `/api/agent/audit-log`, `/api/agent/reindex`) SHALL require `ROLE_ADMIN`. |
| SR-6 | The `/api/auth/verify` endpoint SHALL be public (no authentication required at the BFF level) — it is used by Traefik's ForwardAuth middleware. The JWT validation happens in the Spring Security filter chain before the controller is reached. |
| SR-7 | The `/actuator/health` and `/actuator/info` endpoints SHALL be public for health checks. Other actuator endpoints (`metrics`) SHALL require authentication. |
| SR-8 | The BFF SHALL forward the user's JWT Bearer token to the Python agent service in the `Authorization` header for all proxied agent requests. |
| SR-9 | The BFF SHALL enforce ownership checks on `DELETE /api/user/numbers/{id}` — a user SHALL NOT be able to delete another user's saved numbers. |

## Data Ownership

| ID | Requirement |
|---|---|
| DR-1 | The BFF SHALL own the `app` schema in the shared PostgreSQL database. |
| DR-2 | The BFF SHALL NOT store user passwords or credentials. Keycloak owns identity (users, credentials, roles). |
| DR-3 | The `app.user_profile` table SHALL be keyed by the Keycloak `sub` (subject) claim, not by email or username. |
| DR-4 | The `app.saved_numbers` table SHALL reference `app.user_profile(sub)` via a foreign key with `ON DELETE CASCADE`. |
| DR-5 | The BFF SHALL use Flyway for all schema migrations. Hibernate `ddl-auto` SHALL be set to `validate` — the BFF SHALL NOT auto-create or modify database tables. |

## gRPC Integration

| ID | Requirement |
|---|---|
| GR-1 | The BFF SHALL connect to the Go `lottery-stats-server` via gRPC using a shared `ManagedChannel` (plaintext, within the Docker network). |
| GR-2 | The gRPC contract SHALL be defined in `proto/lottery.proto` at the repository root, shared between Java and Go. |
| GR-3 | The BFF SHALL use a blocking stub (`LotteryServiceGrpc.LotteryServiceBlockingStub`) for synchronous request/response. |
| GR-4 | The BFF SHALL map gRPC status codes to HTTP status codes: `INVALID_ARGUMENT`/`FAILED_PRECONDITION`/`OUT_OF_RANGE` → 400, `NOT_FOUND` → 404, `PERMISSION_DENIED` → 403, `UNAVAILABLE` → 503, `DEADLINE_EXCEEDED` → 504, others → 500. |
| GR-5 | The `ManagedChannel` SHALL be shut down gracefully on application stop. |

## Agent Proxy

| ID | Requirement |
|---|---|
| AR-1 | The BFF SHALL proxy agent requests to the Python agent service via HTTP (`RestClient`). The UI SHALL NOT call the agent service directly. |
| AR-2 | The BFF SHALL forward the user's JWT Bearer token to the agent service in the `Authorization` header. |
| AR-3 | The BFF SHALL use a configurable read timeout (default 300 seconds / 5 minutes) to accommodate long LLM inference times. |
| AR-4 | The agent service base URL SHALL be configurable via `AGENT_SERVICE_URL`. |
| AR-5 | The BFF SHALL propagate upstream agent HTTP errors (`HttpClientErrorException` / `HttpServerErrorException`) to the caller with the upstream status code and body, mapped to an `UPSTREAM_ERROR` `ErrorResponse`. 4xx upstream errors SHALL be logged WARN; 5xx upstream errors SHALL be logged ERROR. |

## Non-Functional Requirements

| ID | Requirement |
|---|---|
| NFR-1 | The BFF SHALL be stateless and horizontally scalable — no server-side sessions, no in-memory user state. |
| NFR-2 | The BFF SHALL use Hibernate `ddl-auto: validate` — schema changes SHALL only be made via Flyway migrations. |
| NFR-3 | The BFF SHALL provide structured error responses with `error` (code), `message`, `status`, `timestamp`, and `path` fields for all exceptions. |
| NFR-4 | The BFF SHALL expose OpenAPI documentation at `/api-docs` and Swagger UI at `/swagger-ui.html`. |
| NFR-5 | The BFF SHALL run as a non-root user in the Docker container. |
| NFR-6 | The BFF SHALL provide a Docker healthcheck via `/actuator/health`. |
| NFR-7 | The BFF SHALL use Spring Boot layered jar extraction for optimized Docker image caching. |
| NFR-8 | The BFF SHALL validate request DTOs using Bean Validation (`@Valid`) and return 400 with field-level error details on validation failure. |
| NFR-9 | The BFF SHALL log all requests with structured logging (user sub, endpoint, intent, result) at INFO level for successful operations and ERROR for failures. |
