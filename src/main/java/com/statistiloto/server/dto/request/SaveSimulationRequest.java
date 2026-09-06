package com.statistiloto.server.dto.request;

import jakarta.validation.constraints.NotNull;

/** Request to save a simulation result for the authenticated user. */
public record SaveSimulationRequest(
    @NotNull String requestJson,
    @NotNull String summaryJson,
    String resultJson
) {}
