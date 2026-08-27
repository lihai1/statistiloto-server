package com.statistiloto.server.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Request to simulate (backtest) a user's numbers against historical draws.
 * Supports systematic forms (6, 8, 10, 12 numbers) where all C(N,6)
 * combinations are played per draw.
 */
public record SimulateRequest(
    @NotEmpty @Size(min = 6, max = 12) List<Integer> form,
    Integer strong,
    LocalDate from,
    LocalDate to,
    Double ticketCost,
    @Size(max = 8) List<Double> prizeAmounts
) {}
