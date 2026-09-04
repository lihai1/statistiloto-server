package com.statistiloto.server.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Request to simulate (backtest) a user's numbers against historical draws.
 * Supports systematic forms (6, 8, 10, 12 numbers) where all C(N,6)
 * combinations are played per draw.
 *
 * <p>Two date windows are supported:
 * <ul>
 *   <li>{@code archiveFrom}/{@code archiveTo} — the historical-draw range
 *       loaded as context (the archive window).</li>
 *   <li>{@code simulateFrom}/{@code simulateTo} — the sub-range of draws to
 *       actually backtest against. When null, the service falls back to the
 *       archive window (preserving the legacy single-window behavior).</li>
 * </ul>
 * This enables a train/test split: study patterns over the archive window,
 * then backtest the chosen numbers over the simulate window.
 */
public record SimulateRequest(
    @NotEmpty @Size(min = 6, max = 12) List<Integer> form,
    Integer strong,
    LocalDate archiveFrom,
    LocalDate archiveTo,
    LocalDate simulateFrom,
    LocalDate simulateTo,
    Double ticketCost,
    @Size(max = 8) List<Double> prizeAmounts
) {}
