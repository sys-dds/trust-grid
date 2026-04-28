package com.trustgrid.api.policyengine;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PolicyExceptionController {
    private final PolicyExceptionService service;

    public PolicyExceptionController(PolicyExceptionService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/policy-exceptions")
    public Map<String, Object> create(@RequestBody Map<String, Object> request) {
        return service.create(request);
    }

    @PostMapping("/api/v1/policy-exceptions/{exceptionId}/approve")
    public Map<String, Object> approve(@PathVariable UUID exceptionId, @RequestBody Map<String, Object> request) {
        return service.approve(exceptionId, request);
    }

    @PostMapping("/api/v1/policy-exceptions/{exceptionId}/reject")
    public Map<String, Object> reject(@PathVariable UUID exceptionId, @RequestBody Map<String, Object> request) {
        return service.reject(exceptionId, request);
    }

    @PostMapping("/api/v1/policy-exceptions/{exceptionId}/revoke")
    public Map<String, Object> revoke(@PathVariable UUID exceptionId, @RequestBody Map<String, Object> request) {
        return service.revoke(exceptionId, request);
    }

    @GetMapping("/api/v1/policy-exceptions")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return service.list(status);
    }

    @GetMapping("/api/v1/policy-exceptions/{exceptionId}")
    public Map<String, Object> get(@PathVariable UUID exceptionId) {
        return service.get(exceptionId);
    }
}
