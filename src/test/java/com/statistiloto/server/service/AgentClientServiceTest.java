package com.statistiloto.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.statistiloto.server.dto.request.AgentApproveRequest;
import com.statistiloto.server.dto.request.AgentChatRequest;
import com.statistiloto.server.dto.request.LlmConfigRequest;
import com.statistiloto.server.dto.response.AgentChatResponse;
import com.statistiloto.server.dto.response.LlmConfigResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for {@link AgentClientService}.
 *
 * <p>Uses OkHttp {@link MockWebServer} to simulate the Python agent service.
 * Each test enqueues a canned HTTP response, invokes the service method, then
 * verifies the recorded request (method, path, Authorization header).
 */
class AgentClientServiceTest {

    private static MockWebServer mockWebServer;

    private AgentClientService agentClientService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void startServer() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        mockWebServer.shutdown();
    }

    @BeforeEach
    void createService() {
        String baseUrl = mockWebServer.url("/").toString();
        agentClientService = new AgentClientService(baseUrl, 300000, ""); // No Redis in tests
    }

    // ── chat() ───────────────────────────────────────────────────────────

    @Test
    void chat_sendsPostWithAuthorizationHeader() throws Exception {
        AgentChatResponse mockResponse = new AgentChatResponse("Hello back", "thread-1", false);
        mockWebServer.enqueue(new MockResponse()
            .setBody(objectMapper.writeValueAsString(mockResponse))
            .addHeader("Content-Type", "application/json"));

        AgentChatRequest request = new AgentChatRequest("session-1", "Hi", "chat", null, null, null);
        String authHeader = "Bearer test-token";
        AgentChatResponse result = agentClientService.chat(request, authHeader);

        assertNotNull(result);
        assertEquals("Hello back", result.response());
        assertEquals("thread-1", result.threadId());
        assertFalse(result.paused());

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/chat", recorded.getPath());
        assertEquals(authHeader, recorded.getHeader("Authorization"));
    }

    // ── approve() ────────────────────────────────────────────────────────

    @Test
    void approve_sendsPostWithAuthorizationHeader() throws Exception {
        AgentChatResponse mockResponse = new AgentChatResponse("Approved", "thread-1", false);
        mockWebServer.enqueue(new MockResponse()
            .setBody(objectMapper.writeValueAsString(mockResponse))
            .addHeader("Content-Type", "application/json"));

        AgentApproveRequest request = new AgentApproveRequest("session-1", true, null);
        String authHeader = "Bearer test-token";
        AgentChatResponse result = agentClientService.approve(request, authHeader);

        assertNotNull(result);
        assertEquals("Approved", result.response());

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/approve", recorded.getPath());
        assertEquals(authHeader, recorded.getHeader("Authorization"));
    }

    // ── getLlmConfig() ───────────────────────────────────────────────────

    @Test
    void getLlmConfig_sendsGetWithAuthorizationHeader() throws Exception {
        LlmConfigResponse mockResponse = new LlmConfigResponse("openai", "gpt-4", "https://api.openai.com", null, 300, 256, null, "active", null);
        mockWebServer.enqueue(new MockResponse()
            .setBody(objectMapper.writeValueAsString(mockResponse))
            .addHeader("Content-Type", "application/json"));

        String authHeader = "Bearer test-token";
        LlmConfigResponse result = agentClientService.getLlmConfig(authHeader);

        assertNotNull(result);
        assertEquals("openai", result.provider());
        assertEquals("gpt-4", result.model());
        assertEquals("active", result.status());

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertEquals("GET", recorded.getMethod());
        assertEquals("/llm-config", recorded.getPath());
        assertEquals(authHeader, recorded.getHeader("Authorization"));
    }

    // ── updateLlmConfig() ────────────────────────────────────────────────

    @Test
    void updateLlmConfig_sendsPutWithAuthorizationHeader() throws Exception {
        LlmConfigResponse mockResponse = new LlmConfigResponse("openai", "gpt-4", "https://api.openai.com", null, 300, 256, null, "updated", null);
        mockWebServer.enqueue(new MockResponse()
            .setBody(objectMapper.writeValueAsString(mockResponse))
            .addHeader("Content-Type", "application/json"));

        LlmConfigRequest request = new LlmConfigRequest("openai", "gpt-4", "https://api.openai.com", null, 300, 256, null);
        String authHeader = "Bearer test-token";
        LlmConfigResponse result = agentClientService.updateLlmConfig(request, authHeader);

        assertNotNull(result);
        assertEquals("openai", result.provider());
        assertEquals("gpt-4", result.model());
        assertEquals("updated", result.status());

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertEquals("PUT", recorded.getMethod());
        assertEquals("/llm-config", recorded.getPath());
        assertEquals(authHeader, recorded.getHeader("Authorization"));
    }

    // ── health() ─────────────────────────────────────────────────────────

    @Test
    void health_sendsGetToHealthz() throws Exception {
        mockWebServer.enqueue(new MockResponse()
            .setBody("{\"status\":\"ok\"}")
            .addHeader("Content-Type", "application/json"));

        String result = agentClientService.health();

        assertEquals("{\"status\":\"ok\"}", result);

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertEquals("GET", recorded.getMethod());
        assertEquals("/healthz", recorded.getPath());
    }

    @Test
    void health_returnsUnavailableWhenAgentUnreachable() {
        // Point to a port where nothing is listening — connection refused is immediate.
        AgentClientService unreachableService = new AgentClientService("http://localhost:1", 300000, "");

        String result = unreachableService.health();

        assertEquals("{\"status\":\"unavailable\"}", result);
    }

    // ── getFreeLlmToggle() ───────────────────────────────────────────────

    @Test
    void getFreeLlmToggle_sendsGetWithAuthorizationHeader() throws Exception {
        mockWebServer.enqueue(new MockResponse()
            .setBody("{\"enabled\":false}")
            .addHeader("Content-Type", "application/json"));

        String authHeader = "Bearer test-token";
        String result = agentClientService.getFreeLlmToggle(authHeader);

        assertEquals("{\"enabled\":false}", result);

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertEquals("GET", recorded.getMethod());
        assertEquals("/free-llm", recorded.getPath());
        assertEquals(authHeader, recorded.getHeader("Authorization"));
    }

    // ── setFreeLlmToggle() ───────────────────────────────────────────────

    @Test
    void setFreeLlmToggle_sendsPutWithAuthorizationHeader() throws Exception {
        mockWebServer.enqueue(new MockResponse()
            .setBody("{\"enabled\":true,\"updated_by\":\"admin-1\"}")
            .addHeader("Content-Type", "application/json"));

        String authHeader = "Bearer test-token";
        String body = "{\"enabled\":true}";
        String result = agentClientService.setFreeLlmToggle(authHeader, body);

        assertEquals("{\"enabled\":true,\"updated_by\":\"admin-1\"}", result);

        RecordedRequest recorded = mockWebServer.takeRequest();
        assertEquals("PUT", recorded.getMethod());
        assertEquals("/free-llm", recorded.getPath());
        assertEquals(authHeader, recorded.getHeader("Authorization"));
        assertEquals("application/json", recorded.getHeader("Content-Type"));
        assertEquals(body, recorded.getBody().readUtf8());
    }
}
