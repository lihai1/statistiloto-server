package com.statistiloto.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ScraperStatusResponse(
    @JsonProperty("request_id") String requestId,
    @JsonProperty("status") String status,
    @JsonProperty("phase") String phase,
    @JsonProperty("inserted") Integer inserted,
    @JsonProperty("prizes_written") Integer prizesWritten,
    @JsonProperty("error") String error,
    @JsonProperty("updated_at") Long updatedAt
) {}
