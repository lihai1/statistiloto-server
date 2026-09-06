package com.statistiloto.server.dto.response;

import java.util.List;

/** Generic lottery computation result (from the Go service). */
public record LotteryResultResponse(
    List<List<Integer>> forms,
    List<PairResponse> pairs,
    List<FrequencyGroupResponse> frequencyGroups,
    Integer totalDrawsInRange
) {
    /** Backward-compatible constructor (totalDrawsInRange defaults to null). */
    public LotteryResultResponse(
        List<List<Integer>> forms,
        List<PairResponse> pairs,
        List<FrequencyGroupResponse> frequencyGroups
    ) {
        this(forms, pairs, frequencyGroups, null);
    }
}
