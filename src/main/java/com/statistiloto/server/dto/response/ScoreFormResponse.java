package com.statistiloto.server.dto.response;

/**
 * Absolute heat index for a number set: observed pair hits vs. the random
 * expectation over the archive window, scaled so 100 = average.
 */
public record ScoreFormResponse(
    double heat,
    long observedPairHits,
    double expectedPairHits,
    int draws,
    int pairCount
) {}
