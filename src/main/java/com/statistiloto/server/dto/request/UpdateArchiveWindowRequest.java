package com.statistiloto.server.dto.request;

import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

/**
 * Request body for PUT /api/me/archive — updates the user's preferred
 * archive date range. Null fields mean "clear / use defaults".
 */
public record UpdateArchiveWindowRequest(
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "from must be YYYY-MM-DD")
    String from,
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "to must be YYYY-MM-DD")
    String to
) {
    public LocalDate fromDate() {
        return from == null || from.isBlank() ? null : LocalDate.parse(from);
    }

    public LocalDate toDate() {
        return to == null || to.isBlank() ? null : LocalDate.parse(to);
    }
}
