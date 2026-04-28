package com.trustgrid.api.appeal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record CreateAppealRequest(@NotBlank String targetType, @NotNull UUID targetId,
                                  @NotBlank String appealReason, Map<String, Object> metadata) {
}
