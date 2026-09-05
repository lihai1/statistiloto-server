package com.statistiloto.server.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Request to submit user feedback or a lottery suggestion. */
public record FeedbackRequest(
    @NotBlank String type,
    @NotBlank String message,
    String page,
    String language,
    String tier,
    String extra
) {}
