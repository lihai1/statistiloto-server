package com.statistiloto.server.dto.response;

import java.util.List;

/** Response from the simulate (backtest) endpoint. */
public record SimulateResultResponse(
    List<SimulateDrawResultResponse> draws,
    SimulateSummaryResponse summary
) {}
