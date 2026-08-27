package com.statistiloto.server.dto.response;

/** A prize tier hit in a single draw (count of combinations + prize amount). */
public record SimulateTierHitResponse(
    int tier,
    int hits,
    double amountPerHit,
    double total
) {}
