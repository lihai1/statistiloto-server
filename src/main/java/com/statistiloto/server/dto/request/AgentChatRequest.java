package com.statistiloto.server.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/** Request to send a message to the AI agent. */
public record AgentChatRequest(
    @NotBlank @JsonProperty("session_id") @JsonAlias("sessionId") String sessionId,
    @NotBlank String message,
    String intent,
    Map<String, Object> context,
    @JsonProperty("config_id") @JsonAlias("configId") Integer configId,
    String lang
) {}
