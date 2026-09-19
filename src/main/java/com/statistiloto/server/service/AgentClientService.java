package com.statistiloto.server.service;

import com.statistiloto.server.dto.request.AgentApproveRequest;
import com.statistiloto.server.dto.request.AgentChatRequest;
import com.statistiloto.server.dto.request.LlmConfigRequest;
import com.statistiloto.server.dto.response.AgentChatResponse;
import com.statistiloto.server.dto.response.LlmConfigResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * HTTP client to the Python agent service.
 * Forwards requests with the user's JWT Bearer token for authentication.
 *
 * <p>Request DTOs use @JsonProperty for snake_case serialization (Python API expects
 * session_id, base_url, api_key) and @JsonAlias to also accept camelCase from the UI.
 *
 * <p>All outbound calls flow through the private {@code get}/{@code post}/{@code put}/
 * {@code delete} helpers so auth-header attachment and response retrieval are uniform.
 * Exception mapping is handled centrally by {@code GlobalExceptionHandler}.
 */
@Service
@Slf4j
public class AgentClientService {

    private final RestClient restClient;
    private final String agentUrl;
    private final HttpClient streamingClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String redisUrl;
    private volatile RedisClient redisClient;
    private volatile long nextRedisProbeAt = 0L;

    /** Re-probe Redis at most every 30s after a failure (was: cached forever). */
    private static final long REDIS_PROBE_BACKOFF_MS = 30_000L;
    /** XREAD BLOCK slice — short enough that emitter cancellation is noticed quickly. */
    private static final long XREAD_BLOCK_MS = 5_000L;
    /** No event (including heartbeats) for this long → terminal error event. */
    private static final long DEFAULT_STREAM_IDLE_TIMEOUT_MS = 45_000L;
    /** Hard cap on a relayed run (matches the SSE emitter timeout). */
    private static final long STREAM_RUN_CAP_MS = 300_000L;

    /** Idle timeout for the stream relay — injectable for tests. */
    private final long streamIdleTimeoutMs;

    @Autowired
    public AgentClientService(
        @Value("${agent.service.url:http://agent:8000}") String agentUrl,
        @Value("${agent.read-timeout-ms:300000}") int readTimeoutMs,
        @Value("${redis.url:}") String redisUrl
    ) {
        this(agentUrl, readTimeoutMs, redisUrl, DEFAULT_STREAM_IDLE_TIMEOUT_MS);
    }

    /** Test-visible constructor allowing a shorter stream idle timeout. */
    AgentClientService(String agentUrl, int readTimeoutMs, String redisUrl, long streamIdleTimeoutMs) {
        this.agentUrl = agentUrl;
        this.redisUrl = redisUrl;
        this.streamIdleTimeoutMs = streamIdleTimeoutMs;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        // LLM inference (especially small local models on limited hardware) can take
        // several minutes. Configurable via agent.read-timeout-ms property / AGENT_READ_TIMEOUT_MS env.
        requestFactory.setReadTimeout(readTimeoutMs);

        this.restClient = RestClient.builder()
            .baseUrl(agentUrl)
            .requestFactory(requestFactory)
            .build();
        // Separate HTTP client for SSE streaming — no read timeout so long-running
        // LLM streams don't get cut. Connect timeout is short for fast failure.
        // HTTP/1.1 is required because uvicorn (the agent's ASGI server) doesn't
        // support HTTP/2 upgrade requests — with the default HTTP/2 the upgrade
        // request is rejected and the Authorization header is lost, resulting in
        // 422 Unprocessable Entity at the agent.
        this.streamingClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        log.info("[agent-client] Initialized base_url={} connect_timeout=5000ms read_timeout={}ms", agentUrl, readTimeoutMs);
    }

    // ── Chat / approve ─────────────────────────────────────────────

    public AgentChatResponse chat(AgentChatRequest req, String authHeader) {
        log.info("[agent-client.chat] START session={} intent={}", req.sessionId(), req.intent());
        AgentChatResponse result = post("/chat", authHeader, req, AgentChatResponse.class);
        log.info("[agent-client.chat] SUCCESS session={} paused={}", req.sessionId(), result != null && result.paused());
        return result;
    }

    public AgentChatResponse approve(AgentApproveRequest req, String authHeader) {
        log.info("[agent-client.approve] START session={} approved={}", req.sessionId(), req.approved());
        AgentChatResponse result = post("/approve", authHeader, req, AgentChatResponse.class);
        log.info("[agent-client.approve] SUCCESS session={}", req.sessionId());
        return result;
    }

    // ── Chat streaming (SSE) ───────────────────────────────────────

    /**
     * Minimal event sink the stream relay writes to. Decouples the relay
     * (XREAD loop, idle watchdog, terminal-event guarantee) from
     * {@link SseEmitter} so it is unit-testable without a servlet container.
     */
    interface StreamSink {
        void send(String event, String data) throws java.io.IOException;
        void complete();
        void completeWithError(Throwable t);
        /** Register cleanup on client disconnect, completion, or timeout. */
        void onClose(Runnable cleanup);
    }

    /** Adapts a Spring {@link SseEmitter} to the relay's {@link StreamSink}. */
    private static class SseEmitterSink implements StreamSink {
        private final SseEmitter emitter;

        SseEmitterSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void send(String event, String data) throws java.io.IOException {
            emitter.send(SseEmitter.event().name(event).data(data));
        }

        @Override
        public void complete() {
            emitter.complete();
        }

        @Override
        public void completeWithError(Throwable t) {
            emitter.completeWithError(t);
        }

        @Override
        public void onClose(Runnable cleanup) {
            emitter.onCompletion(cleanup);
            emitter.onTimeout(cleanup);
            emitter.onError(e -> cleanup.run());
        }
    }

    /**
     * Stream SSE events from the Python agent's /chat/stream endpoint.
     *
     * <p>If Redis is available, the agent returns {thread_id, channel} immediately
     * and appends events to the Redis Stream at that key. This method XREADs the
     * stream from 0-0 (replayable — events published before we start reading are
     * not lost) and forwards them via SSE. Falls back to the inline SSE relay
     * when Redis is unavailable.
     *
     * <p>Events forwarded as-is (event name + data JSON):
     * <ul>
     *   <li>progress — {node, label} — emitted when a step starts</li>
     *   <li>token — {delta} — streamed LLM answer chunks</li>
     *   <li>heartbeat — {} — liveness, resets the idle watchdog</li>
     *   <li>paused — {thread_id, action} — HITL pause with planned action</li>
     *   <li>done — {response, thread_id}</li>
     *   <li>error — {message}</li>
     * </ul>
     */
    public SseEmitter chatStream(AgentChatRequest req, String authHeader) {
        log.info("[agent-client.chat-stream] START session={} intent={}", req.sessionId(), req.intent());
        SseEmitter emitter = new SseEmitter(STREAM_RUN_CAP_MS);
        chatStream(req, authHeader, new SseEmitterSink(emitter));
        return emitter;
    }

    /** Package-private overload for tests — relays into any StreamSink. */
    void chatStream(AgentChatRequest req, String authHeader, StreamSink sink) {
        startStreamRelay("/chat/stream", toJson(req), authHeader, sink, req.sessionId());
    }

    /**
     * Stream SSE events for a HITL approve/resume via the agent's
     * /approve/stream endpoint — same transport contract as chatStream.
     */
    public SseEmitter approveStream(AgentApproveRequest req, String authHeader) {
        log.info("[agent-client.approve-stream] START session={} approved={}", req.sessionId(), req.approved());
        SseEmitter emitter = new SseEmitter(STREAM_RUN_CAP_MS);
        approveStream(req, authHeader, new SseEmitterSink(emitter));
        return emitter;
    }

    /** Package-private overload for tests — relays into any StreamSink. */
    void approveStream(AgentApproveRequest req, String authHeader, StreamSink sink) {
        startStreamRelay("/approve/stream", toJson(req), authHeader, sink, req.sessionId());
    }

    /**
     * Pick the transport and start the relay. Redis Streams is preferred:
     * it is replayable, so events published before the BFF starts reading
     * (fast deterministic paths finish in milliseconds) are not lost.
     */
    private void startStreamRelay(String path, String body, String authHeader,
                                  StreamSink sink, String sessionId) {
        if (isRedisAvailable()) {
            try {
                relayViaRedisStream(path, body, authHeader, sink, sessionId);
                return;
            } catch (Exception e) {
                log.warn("[agent-client.stream] Redis relay failed to start session={} msg={} — falling back to inline SSE",
                    sessionId, e.getMessage());
            }
        }
        relayInlineSse(path, body, authHeader, sink, sessionId);
    }

    /**
     * Redis Streams relay: POST to the agent, read back {thread_id, channel},
     * then XREAD the stream from 0-0 on a dedicated virtual thread.
     *
     * <p>On a non-200 upstream response, or a 200 with no channel, an `error`
     * event is emitted and the emitter completes — the agent has already
     * started the graph at that point, so re-POSTing would run it twice.
     */
    private void relayViaRedisStream(String path, String body, String authHeader,
                                     StreamSink sink, String sessionId) {
        streamingClient.sendAsync(
            HttpRequest.newBuilder()
                .uri(URI.create(agentUrl + path))
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .header(HttpHeaders.ACCEPT, "application/json")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        ).thenAccept(response -> {
            int status = response.statusCode();
            if (status != 200) {
                log.error("[agent-client.stream] UPSTREAM ERROR session={} status={} body={}",
                    sessionId, status, response.body());
                emitSseError(sink, "Upstream error " + status + ": " + response.body());
                return;
            }
            String channel;
            try {
                JsonNode node = objectMapper.readTree(response.body());
                channel = node.hasNonNull("channel") ? node.get("channel").asText() : null;
            } catch (Exception e) {
                channel = null;
            }
            if (channel == null || channel.isBlank()) {
                // 200 but no channel — the graph is already running upstream.
                // Never re-POST: that would execute the run a second time.
                log.error("[agent-client.stream] NO CHANNEL session={} body={}", sessionId, response.body());
                emitSseError(sink, "Stream relay protocol error: missing channel");
                return;
            }
            log.info("[agent-client.stream] Redis relay session={} channel={}", sessionId, channel);
            startXReadLoop(channel, sink, sessionId);
        }).exceptionally(e -> {
            log.error("[agent-client.stream] HTTP ERROR session={} msg={}", sessionId, e.getMessage());
            emitSseError(sink, "Agent stream request failed: " + e.getMessage());
            return null;
        });
    }

    /**
     * Read the Redis Stream on a dedicated virtual thread, forwarding each
     * entry as an SSE event until a terminal event, idle timeout, run cap,
     * or emitter cancellation. A dedicated connection is used because XREAD
     * BLOCK cannot share a connection with other commands.
     */
    private void startXReadLoop(String channel, StreamSink sink, String sessionId) {
        AtomicBoolean stopped = new AtomicBoolean(false);
        StatefulRedisConnection<String, String> conn;
        try {
            conn = redisClient.connect();
        } catch (Exception e) {
            emitSseError(sink, "Redis connection failed: " + e.getMessage());
            return;
        }
        sink.onClose(() -> { stopped.set(true); conn.close(); });
        
        Thread.ofVirtual().name("agent-xread-" + sessionId).start(() -> {
            String lastId = "0-0";
            long startedAt = System.currentTimeMillis();
            long lastEventAt = startedAt;
            try {
                while (!stopped.get()) {
                    List<StreamMessage<String, String>> messages = conn.sync().xread(
                        XReadArgs.Builder.block(XREAD_BLOCK_MS).count(100),
                        XReadArgs.StreamOffset.from(channel, lastId));
                    if (stopped.get()) {
                        break;
                    }
                    if (messages != null) {
                        for (StreamMessage<String, String> m : messages) {
                            lastId = m.getId();
                            Map<String, String> fields = m.getBody();
                            String data = fields != null ? fields.get("data") : null;
                            if (data == null) {
                                continue;
                            }
                            String type = eventType(data);
                            if (type == null) {
                                continue;
                            }
                            lastEventAt = System.currentTimeMillis();
                            sink.send(type, data);
                            if (isTerminalEvent(type)) {
                                sink.complete();
                                log.info("[agent-client.stream] COMPLETE session={} event={}", sessionId, type);
                                return;
                            }
                        }
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastEventAt > streamIdleTimeoutMs) {
                        log.warn("[agent-client.stream] IDLE TIMEOUT session={} idleMs={}", sessionId, now - lastEventAt);
                        emitSseError(sink, "Stream idle timeout: no events for " + (streamIdleTimeoutMs / 1000) + "s");
                        return;
                    }
                    if (now - startedAt > STREAM_RUN_CAP_MS) {
                        log.warn("[agent-client.stream] RUN CAP session={}", sessionId);
                        emitSseError(sink, "Stream run timeout");
                        return;
                    }
                }
            } catch (IllegalStateException e) {
                // Emitter already completed — benign race between a terminal
                // event and client disconnect; nothing to deliver.
                log.debug("[agent-client.stream] emitter already completed session={}", sessionId);
            } catch (Exception e) {
                if (!stopped.get()) {
                    log.error("[agent-client.stream] XREAD ERROR session={} msg={}", sessionId, e.getMessage());
                    emitSseError(sink, "Stream relay error: " + e.getMessage());
                }
            } finally {
                try { conn.close(); } catch (Exception ignored) {}
            }
        });
    }

    private static boolean isTerminalEvent(String type) {
        return "done".equals(type) || "error".equals(type) || "paused".equals(type);
    }

    private String eventType(String data) {
        try {
            JsonNode node = objectMapper.readTree(data);
            return node.hasNonNull("event") ? node.get("event").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Emit a terminal `error` SSE event and complete the emitter. The UI must
     * always receive a terminal event — a silent close leaves it loading forever.
     */
    private void emitSseError(StreamSink sink, String message) {
        try {
            String safe = message == null ? "unknown error"
                : message.replace("\"", "'").replace("\n", " ").replace("\r", " ");
            if (safe.length() > 500) {
                safe = safe.substring(0, 500);
            }
            sink.send("error", "{\"message\":\"" + safe + "\"}");
            sink.complete();
        } catch (Exception e) {
            try { sink.completeWithError(e); } catch (Exception ignored) {}
        }
    }

    /**
     * Inline SSE relay (fallback when Redis is unavailable).
     * Reads the agent's SSE stream directly and forwards events.
     */
    private void relayInlineSse(String path, String body, String authHeader,
                                StreamSink sink, String sessionId) {
        log.info("[agent-client.stream] Using inline SSE relay session={}", sessionId);
        streamingClient.sendAsync(
            HttpRequest.newBuilder()
                .uri(URI.create(agentUrl + path))
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .header(HttpHeaders.ACCEPT, "text/event-stream")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofInputStream()
        ).thenAccept(response -> {
            int status = response.statusCode();
            if (status != 200) {
                String respBody = "";
                try (var is = response.body()) {
                    respBody = new String(is.readAllBytes());
                } catch (Exception ignored) {}
                log.error("[agent-client.stream] UPSTREAM ERROR session={} status={} body={}", sessionId, status, respBody);
                emitSseError(sink, "Upstream error " + status + ": " + respBody);
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body()))) {
                String event = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                        event = line.substring(7).trim();
                    } else if (line.startsWith("data: ") && event != null) {
                        String data = line.substring(6);
                        sink.send(event, data);
                        event = null;
                    }
                }
                sink.complete();
                log.info("[agent-client.stream] SUCCESS session={}", sessionId);
            } catch (Exception e) {
                log.error("[agent-client.stream] ERROR session={} msg={}", sessionId, e.getMessage());
                sink.completeWithError(e);
            }
        }).exceptionally(e -> {
            log.error("[agent-client.stream] HTTP ERROR session={} msg={}", sessionId, e.getMessage());
            emitSseError(sink, "Agent stream request failed: " + e.getMessage());
            return null;
        });
    }

    /**
     * Check if Redis is available (URL configured and connection succeeds).
     * A failed probe backs off for {@link #REDIS_PROBE_BACKOFF_MS} and is then
     * retried — a transient Redis outage must not disable streaming forever.
     */
    private boolean isRedisAvailable() {
        if (redisUrl == null || redisUrl.isBlank()) return false;
        if (redisClient != null) return true;
        long now = System.currentTimeMillis();
        if (now < nextRedisProbeAt) return false;
        synchronized (this) {
            if (redisClient != null) return true;
            if (now < nextRedisProbeAt) return false;
            nextRedisProbeAt = now + REDIS_PROBE_BACKOFF_MS;
            try {
                RedisClient client = RedisClient.create(redisUrl);
                try (StatefulRedisConnection<String, String> conn = client.connect()) {
                    conn.sync().ping();
                }
                redisClient = client;
                log.info("[agent-client] Redis connected: {}", redisUrl);
            } catch (Exception e) {
                log.warn("[agent-client] Redis unavailable, retry in {}ms: {}", REDIS_PROBE_BACKOFF_MS, e.getMessage());
            }
            return redisClient != null;
        }
    }

    private String toJson(Object req) {
        try {
            return objectMapper.writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize agent request", e);
        }
    }

    // ── LLM config (active) ────────────────────────────────────────

    public LlmConfigResponse getLlmConfig(String authHeader) {
        log.info("[agent-client.llm-config] GET START");
        LlmConfigResponse result = get("/llm-config", authHeader, LlmConfigResponse.class);
        log.info("[agent-client.llm-config] GET SUCCESS provider={} model={}",
            result != null ? result.provider() : "?", result != null ? result.model() : "?");
        return result;
    }

    public LlmConfigResponse updateLlmConfig(LlmConfigRequest req, String authHeader) {
        log.info("[agent-client.llm-config] UPDATE START provider={} model={}", req.provider(), req.model());
        LlmConfigResponse result = put("/llm-config", authHeader, req, LlmConfigResponse.class);
        log.info("[agent-client.llm-config] UPDATE SUCCESS status={}", result != null ? result.status() : "?");
        return result;
    }

    // ── Health (no auth) ───────────────────────────────────────────

    public String health() {
        try {
            return restClient.get()
                .uri("/healthz")
                .retrieve()
                .body(String.class);
        } catch (Exception e) {
            log.warn("[agent-client.health] Agent service unreachable: {}", e.getMessage());
            return "{\"status\":\"unavailable\"}";
        }
    }

    // ── Admin: token-usage, audit-log, reindex ─────────────────────

    public String getTokenUsage(String authHeader) {
        log.info("[agent-client.token-usage] GET START");
        String result = get("/token-usage", authHeader, String.class);
        log.info("[agent-client.token-usage] GET SUCCESS");
        return result;
    }

    public String getAuditLog(String authHeader, int limit) {
        log.info("[agent-client.audit-log] GET START limit={}", limit);
        String result = get(b -> b.path("/audit-log").queryParam("limit", limit).build(), authHeader, String.class);
        log.info("[agent-client.audit-log] GET SUCCESS");
        return result;
    }

    public String reindexDocs(String authHeader) {
        log.info("[agent-client.reindex] POST START");
        String result = post("/reindex", authHeader, String.class);
        log.info("[agent-client.reindex] POST SUCCESS");
        return result;
    }

    // ── LLM models / stored configs ────────────────────────────────

    public String listLlmModels(String authHeader, String provider, String baseUrl) {
        log.info("[agent-client.llm-models] GET START provider={} baseUrl={}", provider, baseUrl);
        String result;
        if (baseUrl != null && !baseUrl.isBlank()) {
            result = get(b -> b.path("/llm-models")
                    .queryParam("provider", provider)
                    .queryParam("base_url", baseUrl)
                    .build(), authHeader, String.class);
        } else {
            result = get(b -> b.path("/llm-models")
                    .queryParam("provider", provider)
                    .build(), authHeader, String.class);
        }
        log.info("[agent-client.llm-models] GET SUCCESS");
        return result;
    }

    public String listLlmConfigs(String authHeader) {
        log.info("[agent-client.llm-configs] LIST START");
        String result = get("/llm-configs", authHeader, String.class);
        log.info("[agent-client.llm-configs] LIST SUCCESS");
        return result;
    }

    public String createLlmConfig(String authHeader, String body) {
        log.info("[agent-client.llm-configs] CREATE START");
        String result = restClient.post()
            .uri("/llm-configs")
            .header(HttpHeaders.AUTHORIZATION, authHeader)
            .header(HttpHeaders.ACCEPT, "application/json")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .body(body)
            .retrieve()
            .body(String.class);
        log.info("[agent-client.llm-configs] CREATE SUCCESS");
        return result;
    }

    public String updateLlmConfig(String authHeader, int configId, String body) {
        log.info("[agent-client.llm-configs] UPDATE START id={}", configId);
        String result = restClient.put()
            .uri("/llm-configs/{id}", configId)
            .header(HttpHeaders.AUTHORIZATION, authHeader)
            .header(HttpHeaders.ACCEPT, "application/json")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .body(body)
            .retrieve()
            .body(String.class);
        log.info("[agent-client.llm-configs] UPDATE SUCCESS id={}", configId);
        return result;
    }

    public String activateLlmConfig(String authHeader, int configId) {
        log.info("[agent-client.llm-configs] ACTIVATE START id={}", configId);
        String result = put(b -> b.path("/llm-configs/{id}/activate").build(configId), authHeader, String.class);
        log.info("[agent-client.llm-configs] ACTIVATE SUCCESS id={}", configId);
        return result;
    }

    public String testLlmConfig(String authHeader, int configId) {
        log.info("[agent-client.llm-configs] TEST START id={}", configId);
        String result = post(b -> b.path("/llm-configs/{id}/test").build(configId), authHeader, String.class);
        log.info("[agent-client.llm-configs] TEST SUCCESS id={}", configId);
        return result;
    }

    public void deleteLlmConfig(String authHeader, int configId) {
        log.info("[agent-client.llm-configs] DELETE START id={}", configId);
        deleteBodiless(b -> b.path("/llm-configs/{id}").build(configId), authHeader);
        log.info("[agent-client.llm-configs] DELETE SUCCESS id={}", configId);
    }

    // ── Sessions ───────────────────────────────────────────────────

    public String getFreeLlmToggle(String authHeader) {
        log.info("[agent-client.free-llm] GET START");
        String result = get("/free-llm", authHeader, String.class);
        log.info("[agent-client.free-llm] GET SUCCESS");
        return result;
    }

    public String setFreeLlmToggle(String authHeader, String body) {
        log.info("[agent-client.free-llm] PUT START");
        String result = restClient.put()
            .uri("/free-llm")
            .header(HttpHeaders.AUTHORIZATION, authHeader)
            .header(HttpHeaders.ACCEPT, "application/json")
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .body(body)
            .retrieve()
            .body(String.class);
        log.info("[agent-client.free-llm] PUT SUCCESS");
        return result;
    }

    public String listSessions(String authHeader) {
        log.info("[agent-client.sessions] LIST START");
        String result = get("/sessions", authHeader, String.class);
        log.info("[agent-client.sessions] LIST SUCCESS");
        return result;
    }

    public String getSession(String authHeader, String sessionId) {
        log.info("[agent-client.sessions] GET START session={}", sessionId);
        String result = get(b -> b.path("/sessions/{sessionId}").build(sessionId), authHeader, String.class);
        log.info("[agent-client.sessions] GET SUCCESS session={}", sessionId);
        return result;
    }

    public void deleteSession(String authHeader, String sessionId) {
        log.info("[agent-client.sessions] DELETE START session={}", sessionId);
        deleteBodiless(b -> b.path("/sessions/{sessionId}").build(sessionId), authHeader);
        log.info("[agent-client.sessions] DELETE SUCCESS session={}", sessionId);
    }

    public String deleteAllSessions(String authHeader) {
        log.info("[agent-client.sessions] DELETE-ALL START");
        String result = delete("/sessions", authHeader, String.class);
        log.info("[agent-client.sessions] DELETE-ALL SUCCESS");
        return result;
    }

    // ── Generic helpers ────────────────────────────────────────────

    private <T> T get(String uri, String authHeader, Class<T> type) {
        return restClient.get()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, authHeader)
            .header(HttpHeaders.ACCEPT, "application/json")
            .retrieve()
            .body(type);
    }

    private <T> T get(Function<UriBuilder, URI> uriBuilder, String authHeader, Class<T> type) {
        return restClient.get()
            .uri(uriBuilder)
            .header(HttpHeaders.AUTHORIZATION, authHeader)
            .header(HttpHeaders.ACCEPT, "application/json")
            .retrieve()
            .body(type);
    }

    private <T> T post(String uri, String authHeader, Object body, Class<T> type) {
        return restClient.post()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, authHeader)
            .header(HttpHeaders.ACCEPT, "application/json")
            .body(body)
            .retrieve()
            .body(type);
    }

    /** POST without a body (e.g. reindex, test). */
    private <T> T post(String uri, String authHeader, Class<T> type) {
        return restClient.post()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, authHeader)
            .header(HttpHeaders.ACCEPT, "application/json")
            .retrieve()
            .body(type);
    }

    private <T> T post(Function<UriBuilder, URI> uriBuilder, String authHeader, Class<T> type) {
        return restClient.post()
            .uri(uriBuilder)
            .header(HttpHeaders.AUTHORIZATION, authHeader)
            .header(HttpHeaders.ACCEPT, "application/json")
            .retrieve()
            .body(type);
    }

    private <T> T put(String uri, String authHeader, Object body, Class<T> type) {
        return restClient.put()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, authHeader)
            .header(HttpHeaders.ACCEPT, "application/json")
            .body(body)
            .retrieve()
            .body(type);
    }

    private <T> T put(Function<UriBuilder, URI> uriBuilder, String authHeader, Class<T> type) {
        return restClient.put()
            .uri(uriBuilder)
            .header(HttpHeaders.AUTHORIZATION, authHeader)
            .header(HttpHeaders.ACCEPT, "application/json")
            .retrieve()
            .body(type);
    }

    private <T> T delete(String uri, String authHeader, Class<T> type) {
        return restClient.delete()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, authHeader)
            .header(HttpHeaders.ACCEPT, "application/json")
            .retrieve()
            .body(type);
    }

    private void deleteBodiless(Function<UriBuilder, URI> uriBuilder, String authHeader) {
        restClient.delete()
            .uri(uriBuilder)
            .header(HttpHeaders.AUTHORIZATION, authHeader)
            .header(HttpHeaders.ACCEPT, "application/json")
            .retrieve()
            .toBodilessEntity();
    }
}
