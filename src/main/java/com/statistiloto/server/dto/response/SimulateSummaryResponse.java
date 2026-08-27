package com.statistiloto.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Aggregated summary over all simulated draws. */
public record SimulateSummaryResponse(
    int totalDraws,
    int totalCombinations,
    double totalSpent,
    double totalWon,
    double net,
    List<SimulateTierSummaryResponse> tierSummaries,
    @JsonProperty("drawsWithRealPrizes") int drawsWithRealPrizes
) {}
