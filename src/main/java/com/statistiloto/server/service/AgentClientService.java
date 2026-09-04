package com.statistiloto.server.service;

import com.statistiloto.server.dto.request.AgentApproveRequest;
import com.statistiloto.server.dto.request.AgentChatRequest;
import com.statistiloto.server.dto.request.LlmConfigRequest;
import com.statistiloto.server.dto.response.AgentChatResponse;
import com.statistiloto.server.dto.response.LlmConfigResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class AgentClientService {

    private static final Logger log = LoggerFactory.getLogger(AgentClientService.class);

    private final RestClient restClient;
    private final String agentUrl;
    private final HttpClient streamingClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentClientService(
        @Value("${agent.service.url:http://agent:8000}") String agentUrl,
        @Value("${agent.read-timeout-ms:300000}") int readTimeoutMs
    ) {
        this.agentUrl = agentUrl;
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
     * Stream SSE events from the Python agent's /chat/stream endpoint.
     *
     * <p>Returns a Spring MVC {@link SseEmitter} that relays events from the
     * upstream Python agent. The emitter runs on a background thread so the
     * HTTP response is not blocked. Events are forwarded as-is (event name +
     * data JSON), preserving the upstream schema:
     * <ul>
     *   <li>progress — {node, label}</li>
     *   <li>done — {response, thread_id}</li>
     *   <li>paused — {thread_id}</li>
     *   <li>error — {message}</li>
     * </ul>
     *
     * <p>The existing {@code chat()} method remains unchanged as a fallback.
     */
    public SseEmitter chatStream(AgentChatRequest req, String authHeader) {
        log.info("[agent-client.chat-stream] START session={} intent={}", req.sessionId(), req.intent());
        // Long timeout — LLM inference can take minutes. Matches agent read timeout.
        SseEmitter emitter = new SseEmitter(300_000L);

        streamingClient.sendAsync(
            HttpRequest.newBuilder()
                .uri(URI.create(agentUrl + "/chat/stream"))
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .header(HttpHeaders.ACCEPT, "text/event-stream")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(req)))
                .build(),
            HttpResponse.BodyHandlers.ofInputStream()
        ).thenAccept(response -> {
            int status = response.statusCode();
            if (status != 200) {
                // Upstream returned an error (e.g. 401, 422, 500) — not an SSE stream.
                // Read the error body and forward it as an SSE error event so the UI
                // sees something instead of a silent empty stream.
                String body = "";
                try (var is = response.body()) {
                    body = new String(is.readAllBytes());
                } catch (Exception ignored) {}
                String errMsg = "Upstream error " + status + ": " + body;
                log.error("[agent-client.chat-stream] UPSTREAM ERROR session={} status={} body={}", req.sessionId(), status, body);
                try {
                    emitter.send(SseEmitter.event().name("error").data("{\"message\":\"" + errMsg.replace("\"", "'") + "\"}"));
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
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
                        emitter.send(SseEmitter.event().name(event).data(data));
                        event = null;
                    }
                }
                emitter.complete();
                log.info("[agent-client.chat-stream] SUCCESS session={}", req.sessionId());
            } catch (Exception e) {
                log.error("[agent-client.chat-stream] ERROR session={} msg={}", req.sessionId(), e.getMessage());
                emitter.completeWithError(e);
            }
        }).exceptionally(e -> {
            log.error("[agent-client.chat-stream] HTTP ERROR session={} msg={}", req.sessionId(), e.getMessage());
            emitter.completeWithError(e);
            return null;
        });

        return emitter;
    }

    private String toJson(AgentChatRequest req) {
        try {
            return objectMapper.writeValueAsString(req);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize AgentChatRequest", e);
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
