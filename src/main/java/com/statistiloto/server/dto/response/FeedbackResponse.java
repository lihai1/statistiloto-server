package com.statistiloto.server.dto.response;

import java.time.Instant;

/** Feedback entry — returned to admins viewing the feedback list. */
public record FeedbackResponse(
    Long id,
    String userSub,
    String type,
    String status,
    String page,
    String language,
    String tier,
    String message,
    String extra,
    Instant createdAt
) {}
