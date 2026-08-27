package com.statistiloto.server.dto.response;

/** Aggregated summary for a single prize tier across all simulated draws. */
public record SimulateTierSummaryResponse(
    int tier,
    String label,
    int totalHits,
    double totalAmount
) {}
