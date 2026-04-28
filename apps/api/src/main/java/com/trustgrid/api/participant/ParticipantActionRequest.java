package com.trustgrid.api.participant;

import jakarta.validation.constraints.NotBlank;

public record ParticipantActionRequest(@NotBlank String actor, @NotBlank String reason) {
}
