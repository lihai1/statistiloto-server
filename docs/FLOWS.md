# Flows

## Request Flow: UI → Traefik → BFF → gRPC → Go

```mermaid
sequenceDiagram
    participant UI as Angular UI
    participant TR as Traefik
    participant BFF as Java BFF
    participant GO as Go lottery-stats-server

    UI->>TR: POST /api/generate/form<br/>Authorization: Bearer <JWT><br/>{ howMany, formType, strength, ... }
    TR->>TR: ForwardAuth middleware<br/>calls /api/auth/verify
    TR->>BFF: GET /api/auth/verify<br/>Authorization: Bearer <JWT>
    BFF->>BFF: Spring Security validates JWT<br/>(JWKS signature + audience check)
    BFF-->>TR: 200 { authenticated: true, sub, email }
    TR->>BFF: POST /api/generate/form<br/>Authorization: Bearer <JWT><br/>{ howMany, formType, strength, ... }
    BFF->>BFF: Spring Security validates JWT<br/>Extracts user sub from JWT
    BFF->>BFF: GenerateController.generateForm()<br/>Maps REST DTO → proto GenerateFormRequest
    BFF->>GO: gRPC GenerateForm(GenerateFormRequest)
    GO->>GO: Run lottery-tree algorithm<br/>over historical draws
    GO-->>BFF: GenerateFormResponse<br/>(list of NumberSet)
    BFF->>BFF: Map proto response → LotteryResultResponse
    BFF-->>TR: 200 { forms: [[...], [...]] }
    TR-->>UI: 200 { forms: [[...], [...]] }
```

## Saved Numbers CRUD

```mermaid
sequenceDiagram
    participant UI as Angular UI
    participant TR as Traefik
    participant BFF as Java BFF
    participant DB as PostgreSQL (app schema)

    Note over UI,DB: GET — List saved numbers
    UI->>TR: GET /api/user/numbers<br/>Authorization: Bearer <JWT>
    TR->>BFF: Forward (JWT validated by ForwardAuth)
    BFF->>BFF: Extract user_sub from JWT
    BFF->>DB: SELECT * FROM app.saved_numbers<br/>WHERE user_sub = ? ORDER BY created_at DESC
    DB-->>BFF: Rows
    BFF-->>UI: 200 [ { id, category, numbers, willBe, ... }, ... ]

    Note over UI,DB: POST — Save new numbers
    UI->>TR: POST /api/user/numbers<br/>{ category, numbers, willBe, dateFrom, dateTo }
    TR->>BFF: Forward (JWT validated)
    BFF->>BFF: Extract user_sub from JWT
    BFF->>BFF: Validate numbers contain no internal duplicates
    BFF->>BFF: Validate no exact duplicate (same category, numbers, willBe) for this user
    alt Validation fails
        BFF-->>UI: 400 { error: "BAD_REQUEST", message: "..." }
    else Validation passes
        BFF->>DB: Ensure user_profile row exists<br/>(INSERT IF NOT EXISTS)
        BFF->>DB: INSERT INTO app.saved_numbers<br/>(user_sub, category, numbers, will_be, ...)
    end
    DB-->>BFF: Generated id
    BFF-->>UI: 200 { id, category, numbers, willBe, ... }

    Note over UI,DB: DELETE — Delete saved numbers
    UI->>TR: DELETE /api/user/numbers/42
    TR->>BFF: Forward (JWT validated)
    BFF->>BFF: Extract user_sub from JWT
    BFF->>DB: SELECT * FROM app.saved_numbers WHERE id = 42
    DB-->>BFF: Row (owner = user_sub_A)
    BFF->>BFF: Check: row.user_sub == JWT user_sub?
    alt Owner matches
        BFF->>DB: DELETE FROM app.saved_numbers WHERE id = 42
        BFF-->>UI: 200 (no body)
    else Owner does not match
        BFF-->>UI: 403 { error: "FORBIDDEN", message: "Not authorized..." }
    end
```

## Agent Proxy

The BFF proxies all `/api/agent/*` requests to the Python agent service via HTTP,
forwarding the user's JWT Bearer token. `POST /api/agent/chat/stream` returns an SSE
stream; when `redis.url` is available the BFF uses Redis pub/sub, otherwise it falls
back to inline SSE.
The flow below shows the chat + HITL
approval cycle. The BFF also proxies session management (`/api/agent/sessions`
GET/DELETE), LLM config management (`/api/agent/llm-config` GET/PUT,
`/api/agent/llm-configs` CRUD + activate/test), telemetry (`/token-usage`,
`/audit-log`), RAG reindex, and model listing (`/llm-models`) — all follow the
same pattern: extract JWT → build `Authorization` header → forward to the agent
→ return the agent's JSON response. Admin endpoints are gated by
`@PreAuthorize("hasRole('ADMIN')")`. Upstream 4xx/5xx errors from the agent are
propagated to the caller as `UPSTREAM_ERROR` responses with the original status
code.

```mermaid
sequenceDiagram
    participant UI as Angular UI
    participant TR as Traefik
    participant BFF as Java BFF
    participant AGENT as Python Agent Service<br/>(LangGraph)

    Note over UI,AGENT: Chat request
    UI->>TR: POST /api/agent/chat<br/>Authorization: Bearer <JWT><br/>{ session_id, intent, ... }
    TR->>BFF: Forward (JWT validated by ForwardAuth)
    BFF->>BFF: Extract JWT, build authHeader<br/>= "Bearer " + jwt.getTokenValue()
    BFF->>AGENT: POST /chat<br/>Authorization: Bearer <JWT><br/>Accept: application/json<br/>{ session_id, intent, ... }
    AGENT->>AGENT: LangGraph processes request<br/>(LLM inference, may pause for approval)
    AGENT-->>BFF: 200 { paused: true/false, ... }
    BFF-->>UI: 200 { paused: true/false, ... }

    Note over UI,AGENT: Approval request (if paused)
    UI->>TR: POST /api/agent/approve<br/>Authorization: Bearer <JWT><br/>{ session_id, approved: true }
    TR->>BFF: Forward (JWT validated)
    BFF->>BFF: Extract JWT, build authHeader
    BFF->>AGENT: POST /approve<br/>Authorization: Bearer <JWT><br/>{ session_id, approved: true }
    AGENT->>AGENT: Resume LangGraph execution<br/>with approval decision
    AGENT-->>BFF: 200 { paused: false, ... }
    BFF-->>UI: 200 { paused: false, ... }
```

## Agent Chat Streaming (SSE)

```mermaid
sequenceDiagram
    participant UI as Angular UI
    participant TR as Traefik
    participant BFF as Java BFF
    participant REDIS as Redis
    participant AGENT as Python Agent Service

    UI->>TR: POST /api/agent/chat/stream<br/>Authorization: Bearer <JWT><br/>{ session_id, intent, ... }
    TR->>BFF: Forward (JWT validated)
    BFF->>BFF: Extract JWT, build authHeader
    alt Redis available
        BFF->>AGENT: POST /chat/stream<br/>Authorization: Bearer <JWT><br/>{ session_id, intent, ... }
        AGENT-->>BFF: 200 { thread_id, channel }
        BFF->>REDIS: SUBSCRIBE channel
        loop Events published by agent
            REDIS-->>BFF: { "event": "progress", ... }
            BFF-->>UI: event: progress<br/>data: { ... }
            REDIS-->>BFF: { "event": "done", ... }
            BFF-->>UI: event: done<br/>data: { ... }
            BFF->>REDIS: UNSUBSCRIBE / close
        end
    else Redis unavailable
        BFF->>AGENT: POST /chat/stream<br/>Accept: text/event-stream
        loop Inline SSE lines
            AGENT-->>BFF: event: ...<br/>data: ...
            BFF-->>UI: relay event
        end
    end
```

## Auth Validation (ForwardAuth)

```mermaid
sequenceDiagram
    participant UI as Angular UI
    participant TR as Traefik
    participant BFF as Java BFF
    participant KC as Keycloak (JWKS)

    UI->>TR: GET /api/me<br/>Authorization: Bearer <JWT>
    TR->>TR: ForwardAuth middleware triggered<br/>for /api/** route
    TR->>BFF: GET /api/auth/verify<br/>Authorization: Bearer <JWT><br/>(same headers as original request)

    BFF->>BFF: Spring Security filter chain<br/>OAuth2ResourceServer JWT validator
    BFF->>KC: Fetch JWKS public keys<br/>(cached after first fetch)
    KC-->>BFF: Public keys (RS256)
    BFF->>BFF: Verify JWT signature<br/>using JWKS public key
    BFF->>BFF: Validate audience claim<br/>contains "statistiloto-ui"
    BFF->>BFF: Validate exp / nbf / iss

    alt JWT valid
        BFF-->>TR: 200 { authenticated: true, sub: "...", email: "..." }
        TR->>BFF: GET /api/me<br/>Authorization: Bearer <JWT><br/>(original request forwarded)
        BFF-->>TR: 200 { sub, email, name, roles }
        TR-->>UI: 200 { sub, email, name, roles }
    else JWT invalid or missing
        BFF-->>TR: 401 Unauthorized
        TR-->>UI: 401 Unauthorized (request blocked)
    end
```

## Startup Sequence

```mermaid
sequenceDiagram
    participant DC as Docker Compose
    participant DB as PostgreSQL
    participant KC as Keycloak
    participant GO as Go lottery-stats-server
    participant AGENT as Python Agent Service
    participant BFF as Java BFF

    DC->>DB: Start PostgreSQL
    DB->>DB: Apply db/ init scripts<br/>(create statistiloto DB)
    DB-->>DC: Healthy

    DC->>KC: Start Keycloak
    KC-->>DC: Healthy

    DC->>GO: Start Go lottery-stats-server
    GO->>GO: Load historical draws<br/>Build lottery tree
    GO-->>DC: Healthy (gRPC :9090)

    DC->>AGENT: Start Python agent service
    AGENT-->>DC: Healthy (HTTP :8000)

    DC->>BFF: Start Java BFF
    BFF->>BFF: Spring Boot starts
    BFF->>DB: Flyway migrations<br/>(V1, V2, V3, V4)
    DB->>DB: CREATE/ALTER TABLE app.user_profile<br/>(+ archive_from, archive_to)<br/>CREATE TABLE app.saved_numbers<br/>CREATE TABLE app.saved_simulations<br/>CREATE TABLE app.feedback
    DB-->>BFF: Migrations complete
    BFF->>BFF: Hibernate validate<br/>(ddl-auto: validate)
    BFF->>BFF: Create gRPC ManagedChannel<br/>→ GO host:port
    BFF->>BFF: Create RestClient<br/>→ AGENT base URL
    BFF->>BFF: Create Lettuce RedisClient<br/>→ REDIS_URL (lazy connection test)
    BFF->>BFF: Fetch JWKS from Keycloak<br/>(lazy — on first JWT validation)
    BFF-->>DC: Healthy (HTTP :8082)<br/>/actuator/health → 200

    Note over BFF: BFF does not block startup<br/>waiting for Go/Agent/Keycloak.<br/>JWKS is fetched lazily on first request.<br/>gRPC channel connects lazily.
```
