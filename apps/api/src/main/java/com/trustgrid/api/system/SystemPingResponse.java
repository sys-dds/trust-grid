package com.trustgrid.api.system;

import java.time.Instant;

public record SystemPingResponse(String service, String status, Instant timestamp) {
}
