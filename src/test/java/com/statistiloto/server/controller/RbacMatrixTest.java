package com.statistiloto.server.controller;

import com.statistiloto.server.security.SecurityConfig;
import com.statistiloto.server.service.AgentClientService;
import com.statistiloto.server.service.FeedbackService;
import com.statistiloto.server.service.UserProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role-based access control (RBAC) matrix tests.
 *
 * <p>Uses {@link WebMvcTest} to load only the web layer (controllers + security config).
 * All service beans are mocked with {@link MockBean} so the tests focus purely on the
 * security filter chain and {@code @PreAuthorize} behaviour, not response bodies.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Every admin endpoint ({@code @PreAuthorize("hasRole('ADMIN')")}) rejects
 *       a JWT that only carries {@code ROLE_USER} with HTTP 403.</li>
 *   <li>Every admin endpoint accepts a JWT carrying {@code ROLE_ADMIN} (i.e. does
 *       not return 403 — the response may be 200, 404, or another non-forbidden status).</li>
 *   <li>Protected {@code /api/**} endpoints return 401 when no JWT is supplied.</li>
 *   <li>The {@code /api/auth/verify} ForwardAuth endpoint is public (no 401).</li>
 * </ul>
 */
@WebMvcTest({AgentController.class, FeedbackController.class, UserController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost/auth/realms/statistiloto/protocol/openid-connect/certs",
    "spring.security.oauth2.resourceserver.jwt.audiences=statistiloto-ui"
})
class RbacMatrixTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    AgentClientService agentClientService;

    @MockBean
    FeedbackService feedbackService;

    @MockBean
    UserProfileService userProfileService;

    // ── Admin endpoint catalogue ─────────────────────────────────────────
    //
    // Every endpoint annotated with @PreAuthorize("hasRole('ADMIN')") across the
    // BFF controllers. Each entry pairs an HTTP request builder (with a minimal
    // valid body where required) with the HTTP method + path so the matrix test
    // can drive them uniformly.

    /** A single admin endpoint to probe in the RBAC matrix. */
    record AdminEndpoint(String name, MockHttpServletRequestBuilder request) {}

    static Stream<AdminEndpoint> adminEndpoints() {
        return Stream.of(
            // AgentController — /api/agent
            new AdminEndpoint("GET /api/agent/llm-config",
                get("/api/agent/llm-config")),
            new AdminEndpoint("PUT /api/agent/llm-config",
                put("/api/agent/llm-config").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"provider\":\"openai\",\"model\":\"gpt-4\"}")),
            new AdminEndpoint("GET /api/agent/token-usage",
                get("/api/agent/token-usage")),
            new AdminEndpoint("GET /api/agent/audit-log",
                get("/api/agent/audit-log")),
            new AdminEndpoint("POST /api/agent/reindex",
                post("/api/agent/reindex")),
            new AdminEndpoint("GET /api/agent/llm-models",
                get("/api/agent/llm-models").param("provider", "openai")),
            new AdminEndpoint("GET /api/agent/llm-configs",
                get("/api/agent/llm-configs")),
            new AdminEndpoint("POST /api/agent/llm-configs",
                post("/api/agent/llm-configs").contentType(MediaType.APPLICATION_JSON).content("{}")),
            new AdminEndpoint("PUT /api/agent/llm-configs/{configId}",
                put("/api/agent/llm-configs/1").contentType(MediaType.APPLICATION_JSON).content("{}")),
            new AdminEndpoint("PUT /api/agent/llm-configs/{configId}/activate",
                put("/api/agent/llm-configs/1/activate")),
            new AdminEndpoint("POST /api/agent/llm-configs/{configId}/test",
                post("/api/agent/llm-configs/1/test")),
            new AdminEndpoint("DELETE /api/agent/llm-configs/{configId}",
                delete("/api/agent/llm-configs/1")),
            new AdminEndpoint("GET /api/agent/free-llm",
                get("/api/agent/free-llm")),
            new AdminEndpoint("PUT /api/agent/free-llm",
                put("/api/agent/free-llm").contentType(MediaType.APPLICATION_JSON).content("{}")),
            // FeedbackController — /api/feedback
            new AdminEndpoint("GET /api/feedback",
                get("/api/feedback")),
            new AdminEndpoint("PUT /api/feedback/{id}/status",
                put("/api/feedback/1/status").param("status", "read")),
            new AdminEndpoint("DELETE /api/feedback/{id}",
                delete("/api/feedback/1"))
        );
    }

    // ── 1. USER role is rejected from every admin endpoint ───────────────

    @ParameterizedTest(name = "{0} rejects ROLE_USER with 403")
    @MethodSource("adminEndpoints")
    void userRole_rejectedFromAdminEndpoints(AdminEndpoint endpoint) throws Exception {
        mockMvc.perform(endpoint.request()
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
            .andExpect(status().isForbidden());
    }

    // ── 2. ADMIN role is accepted on every admin endpoint ────────────────

    @ParameterizedTest(name = "{0} accepts ROLE_ADMIN (not 403)")
    @MethodSource("adminEndpoints")
    void adminRole_acceptedOnAdminEndpoints(AdminEndpoint endpoint) throws Exception {
        mockMvc.perform(endpoint.request()
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(result -> {
                int status = result.getResponse().getStatus();
                if (status == 403) {
                    throw new AssertionError(
                        "Expected admin role to be accepted on " + endpoint.name()
                            + " but got 403 Forbidden");
                }
            });
    }

    // ── 3. No auth → 401 on a protected /api/** endpoint ─────────────────

    @Test
    void noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/agent/sessions"))
            .andExpect(status().isUnauthorized());
    }

    // ── 4. /api/auth/verify is public (no 401) ──────────────────────────

    @Test
    void authVerify_isPublic() throws Exception {
        // The ForwardAuth endpoint is permitAll — calling it without a JWT must
        // not yield 401. The controller returns 200 with authenticated=false.
        mockMvc.perform(get("/api/auth/verify"))
            .andExpect(result -> {
                int status = result.getResponse().getStatus();
                if (status == 401) {
                    throw new AssertionError(
                        "Expected /api/auth/verify to be public but got 401 Unauthorized");
                }
            });
    }
}
