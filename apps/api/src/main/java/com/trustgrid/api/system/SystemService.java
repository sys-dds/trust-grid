package com.trustgrid.api.system;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SystemService {

    private final String serviceName;
    private final String nodeId;

    public SystemService(
            @Value("${trustgrid.service-name}") String serviceName,
            @Value("${trustgrid.node-id}") String nodeId
    ) {
        this.serviceName = serviceName;
        this.nodeId = nodeId;
    }

    public SystemPingResponse ping() {
        return new SystemPingResponse(serviceName, "OK", Instant.now());
    }

    public SystemNodeResponse node() {
        return new SystemNodeResponse(
                serviceName,
                nodeId,
                System.getProperty("java.version"),
                "api",
                Instant.now()
        );
    }
}
