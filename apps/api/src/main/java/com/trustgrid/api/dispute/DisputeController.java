package com.trustgrid.api.dispute;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DisputeController {

    private final DisputeService service;

    public DisputeController(DisputeService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/transactions/{transactionId}/disputes")
    public DisputeResponse create(@PathVariable UUID transactionId,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                  @Valid @RequestBody CreateDisputeRequest request) {
        return service.create(transactionId, idempotencyKey, request);
    }

    @PostMapping("/api/v1/disputes/{disputeId}/status")
    public DisputeResponse updateStatus(@PathVariable UUID disputeId,
                                        @Valid @RequestBody DisputeStatusUpdateRequest request) {
        return service.updateStatus(disputeId, request);
    }

    @PostMapping("/api/v1/disputes/{disputeId}/statements")
    public DisputeStatementResponse addStatement(@PathVariable UUID disputeId,
                                                 @Valid @RequestBody DisputeStatementRequest request) {
        return service.addStatement(disputeId, request);
    }

    @GetMapping("/api/v1/disputes/{disputeId}")
    public DisputeResponse get(@PathVariable UUID disputeId) {
        return service.get(disputeId);
    }

    @GetMapping("/api/v1/disputes")
    public List<DisputeResponse> search(@RequestParam(required = false) UUID transactionId,
                                        @RequestParam(required = false) DisputeStatus status) {
        return service.search(transactionId, status);
    }

    @GetMapping("/api/v1/disputes/{disputeId}/evidence-bundle")
    public Map<String, Object> evidenceBundle(@PathVariable UUID disputeId) {
        return service.evidenceBundle(disputeId);
    }

    @PostMapping("/api/v1/disputes/{disputeId}/resolve")
    public DisputeResponse resolve(@PathVariable UUID disputeId,
                                   @Valid @RequestBody ResolveDisputeRequest request) {
        return service.resolve(disputeId, request);
    }

    @GetMapping("/api/v1/disputes/{disputeId}/statements")
    public List<DisputeStatementResponse> statements(@PathVariable UUID disputeId) {
        return service.statements(disputeId);
    }

    @GetMapping("/api/v1/disputes/{disputeId}/deadlines")
    public List<DisputeEvidenceDeadlineResponse> deadlines(@PathVariable UUID disputeId) {
        return service.deadlines(disputeId);
    }
}
