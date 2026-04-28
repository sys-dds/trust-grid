package com.trustgrid.api.ops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record CreateOpsQueueItemRequest(@NotBlank String queueType, @NotBlank String targetType, @NotNull UUID targetId,
                                        @NotBlank String priority, @NotBlank String reason, List<String> signals) {
}
