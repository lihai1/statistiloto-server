package com.statistiloto.server.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

/** Simulation result for a single historical draw. */
public record SimulateDrawResultResponse(
    int drawNumber,
    LocalDate drawDate,
    List<Integer> winningNumbers,
    int winningStrong,
    List<SimulateTierHitResponse> tierHits,
    double prizeWon,
    double ticketCost,
    @JsonProperty("usedRealPrizes") boolean usedRealPrizes
) {}
