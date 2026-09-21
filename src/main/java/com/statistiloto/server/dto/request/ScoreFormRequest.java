package com.statistiloto.server.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** Request to score user-selected numbers (heat index vs. random expectation). */
public record ScoreFormRequest(
    @NotNull @Size(min = 2) List<Integer> form,
    LocalDate from,
    LocalDate to
) {}
