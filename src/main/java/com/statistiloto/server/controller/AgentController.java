package com.statistiloto.server.controller;

import com.statistiloto.server.dto.request.AgentChatRequest;
import com.statistiloto.server.dto.request.AgentApproveRequest;
import com.statistiloto.server.dto.request.LlmConfigRequest;
import com.statistiloto.server.dto.response.AgentChatResponse;
import com.statistiloto.server.dto.response.LlmConfigResponse;
import com.statistiloto.server.service.AgentClientService;
import com.statistiloto.server.util.JwtUtils;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Agent proxy endpoints. These forward to the Python agent service.
 * The UI never calls the agent service directly.
 *
 * <p>Controllers are intentionally thin: extract the JWT, log the start, and
 * delegate to {@link AgentClientService}. Exception logging and HTTP error
 * mapping are handled centrally by {@code GlobalExceptionHandler} and
 * {@code RequestLoggingFilter}, so methods here do not re-catch/re-throw.
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentClientService agentClient;

    public AgentController(AgentClientService agentClient) {
        this.agentClient = agentClient;
    }

    @PostMapping("/chat")
    public AgentChatResponse chat(@AuthenticationPrincipal Jwt jwt,
                                  @Valid @RequestBody AgentChatRequest request) {
        log.info("[agent.chat] START user={} session={} intent={}", JwtUtils.userSub(jwt), request.sessionId(), request.intent());
        return agentClient.chat(request, JwtUtils.bearer(jwt));
    }

    @PostMapping("/approve")
    public AgentChatResponse approve(@AuthenticationPrincipal Jwt jwt,
                                     @Valid @RequestBody AgentApproveRequest request) {
        log.info("[agent.approve] START user={} session={} approved={}", JwtUtils.userSub(jwt), request.sessionId(), request.approved());
        return agentClient.approve(request, JwtUtils.bearer(jwt));
    }

    @GetMapping("/llm-config")
    @PreAuthorize("hasRole('ADMIN')")
    public LlmConfigResponse getLlmConfig(@AuthenticationPrincipal Jwt jwt) {
        log.info("[agent.llm-config] GET user={}", JwtUtils.userSub(jwt));
        return agentClient.getLlmConfig(JwtUtils.bearer(jwt));
    }

    @PutMapping("/llm-config")
    @PreAuthorize("hasRole('ADMIN')")
    public LlmConfigResponse updateLlmConfig(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody LlmConfigRequest request) {
        log.info("[agent.llm-config] UPDATE START user={} provider={} model={}", JwtUtils.userSub(jwt), request.provider(), request.model());
        return agentClient.updateLlmConfig(request, JwtUtils.bearer(jwt));
    }

    @GetMapping("/health")
    public String agentHealth() {
        return agentClient.health();
    }

    @GetMapping("/token-usage")
    @PreAuthorize("hasRole('ADMIN')")
    public String getTokenUsage(@AuthenticationPrincipal Jwt jwt) {
        return agentClient.getTokenUsage(JwtUtils.bearer(jwt));
    }

    @GetMapping("/audit-log")
    @PreAuthorize("hasRole('ADMIN')")
    public String getAuditLog(@AuthenticationPrincipal Jwt jwt,
                              @RequestParam(defaultValue = "50") int limit) {
        return agentClient.getAuditLog(JwtUtils.bearer(jwt), limit);
    }

    @PostMapping("/reindex")
    @PreAuthorize("hasRole('ADMIN')")
    public String reindexDocs(@AuthenticationPrincipal Jwt jwt) {
        return agentClient.reindexDocs(JwtUtils.bearer(jwt));
    }

    @GetMapping("/llm-models")
    @PreAuthorize("hasRole('ADMIN')")
    public String listLlmModels(@AuthenticationPrincipal Jwt jwt,
                                @RequestParam String provider,
                                @RequestParam(name = "base_url", required = false) String baseUrl) {
        return agentClient.listLlmModels(JwtUtils.bearer(jwt), provider, baseUrl);
    }

    @GetMapping("/llm-configs")
    @PreAuthorize("hasRole('ADMIN')")
    public String listLlmConfigs(@AuthenticationPrincipal Jwt jwt) {
        return agentClient.listLlmConfigs(JwtUtils.bearer(jwt));
    }

    @PostMapping("/llm-configs")
    @PreAuthorize("hasRole('ADMIN')")
    public String createLlmConfig(@AuthenticationPrincipal Jwt jwt,
                                  @RequestBody String body) {
        return agentClient.createLlmConfig(JwtUtils.bearer(jwt), body);
    }

    @PutMapping("/llm-configs/{configId}")
    @PreAuthorize("hasRole('ADMIN')")
    public String updateLlmConfig(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable int configId,
                                  @RequestBody String body) {
        return agentClient.updateLlmConfig(JwtUtils.bearer(jwt), configId, body);
    }

    @PutMapping("/llm-configs/{configId}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public String activateLlmConfig(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable int configId) {
        return agentClient.activateLlmConfig(JwtUtils.bearer(jwt), configId);
    }

    @PostMapping("/llm-configs/{configId}/test")
    @PreAuthorize("hasRole('ADMIN')")
    public String testLlmConfig(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable int configId) {
        return agentClient.testLlmConfig(JwtUtils.bearer(jwt), configId);
    }

    @DeleteMapping("/llm-configs/{configId}")
    @PreAuthorize("hasRole('ADMIN')")
    public String deleteLlmConfig(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable int configId) {
        agentClient.deleteLlmConfig(JwtUtils.bearer(jwt), configId);
        return "{\"status\":\"deleted\",\"id\":" + configId + "}";
    }

    @GetMapping("/sessions")
    public String listSessions(@AuthenticationPrincipal Jwt jwt) {
        return agentClient.listSessions(JwtUtils.bearer(jwt));
    }

    @GetMapping("/free-llm")
    @PreAuthorize("hasRole('ADMIN')")
    public String getFreeLlmToggle(@AuthenticationPrincipal Jwt jwt) {
        log.info("[agent.free-llm] GET user={}", JwtUtils.userSub(jwt));
        return agentClient.getFreeLlmToggle(JwtUtils.bearer(jwt));
    }

    @PutMapping("/free-llm")
    @PreAuthorize("hasRole('ADMIN')")
    public String setFreeLlmToggle(@AuthenticationPrincipal Jwt jwt,
                                   @RequestBody String body) {
        log.info("[agent.free-llm] PUT user={}", JwtUtils.userSub(jwt));
        return agentClient.setFreeLlmToggle(JwtUtils.bearer(jwt), body);
    }

    @GetMapping("/sessions/{sessionId}")
    public String getSession(@AuthenticationPrincipal Jwt jwt,
                             @PathVariable String sessionId) {
        return agentClient.getSession(JwtUtils.bearer(jwt), sessionId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public String deleteSession(@AuthenticationPrincipal Jwt jwt,
                                @PathVariable String sessionId) {
        agentClient.deleteSession(JwtUtils.bearer(jwt), sessionId);
        return "{\"status\":\"deleted\",\"session_id\":\"" + sessionId + "\"}";
    }

    @DeleteMapping("/sessions")
    public String deleteAllSessions(@AuthenticationPrincipal Jwt jwt) {
        return agentClient.deleteAllSessions(JwtUtils.bearer(jwt));
    }
}
