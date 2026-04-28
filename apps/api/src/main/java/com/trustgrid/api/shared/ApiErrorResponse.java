package com.trustgrid.api.shared;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String requestId,
        List<FieldErrorResponse> fieldErrors
) {
}
