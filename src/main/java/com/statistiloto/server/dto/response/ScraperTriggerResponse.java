package com.statistiloto.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ScraperTriggerResponse(
    @JsonProperty("request_id") String requestId,
    @JsonProperty("status") String status
) {}
