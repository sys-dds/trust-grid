package com.trustgrid.api.listing;

import jakarta.validation.constraints.NotBlank;

public record ListingActionRequest(@NotBlank String actor, @NotBlank String reason) {
}
