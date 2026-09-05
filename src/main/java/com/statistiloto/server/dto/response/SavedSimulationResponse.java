package com.statistiloto.server.dto.response;

import java.time.Instant;

/** Saved simulation result for a user. */
public record SavedSimulationResponse(
    Long id,
    String requestJson,
    String summaryJson,
    Instant createdAt
) {}
