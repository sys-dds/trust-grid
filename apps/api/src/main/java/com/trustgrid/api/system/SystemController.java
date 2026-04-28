package com.trustgrid.api.system;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final SystemService systemService;
    private final DependencyProbeService dependencyProbeService;
    private final String serviceName;

    public SystemController(
            SystemService systemService,
            DependencyProbeService dependencyProbeService,
            @Value("${trustgrid.service-name}") String serviceName
    ) {
        this.systemService = systemService;
        this.dependencyProbeService = dependencyProbeService;
        this.serviceName = serviceName;
    }

    @GetMapping("/ping")
    public SystemPingResponse ping() {
        return systemService.ping();
    }

    @GetMapping("/node")
    public SystemNodeResponse node() {
        return systemService.node();
    }

    @GetMapping("/dependencies")
    public SystemDependenciesResponse dependencies() {
        return new SystemDependenciesResponse(serviceName, Instant.now(), dependencyProbeService.dependencies());
    }

    @PostMapping("/validation-probe")
    public ValidationProbeResponse validationProbe(@Valid @RequestBody ValidationProbeRequest request) {
        return new ValidationProbeResponse(request.name());
    }

    public record ValidationProbeRequest(@NotBlank String name) {
    }

    public record ValidationProbeResponse(String name) {
    }
}
