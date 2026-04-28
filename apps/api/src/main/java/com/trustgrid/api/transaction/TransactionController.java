package com.trustgrid.api.transaction;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/listings/{listingId}/transactions")
    public TransactionResponse create(@PathVariable UUID listingId,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                      @Valid @RequestBody CreateTransactionRequest request) {
        return service.create(listingId, idempotencyKey, request);
    }

    @GetMapping("/api/v1/transactions/{transactionId}")
    public TransactionResponse get(@PathVariable UUID transactionId) {
        return service.get(transactionId);
    }

    @PostMapping("/api/v1/transactions/{transactionId}/start")
    public TransactionResponse start(@PathVariable UUID transactionId,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                     @Valid @RequestBody TransactionActionRequest request) {
        return service.start(transactionId, idempotencyKey, request);
    }

    @PostMapping("/api/v1/transactions/{transactionId}/mark-shipped")
    public TransactionResponse markShipped(@PathVariable UUID transactionId,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                           @Valid @RequestBody TransactionActionRequest request) {
        return service.markShipped(transactionId, idempotencyKey, request);
    }

    @PostMapping("/api/v1/transactions/{transactionId}/mark-delivered")
    public TransactionResponse markDelivered(@PathVariable UUID transactionId,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                             @Valid @RequestBody TransactionActionRequest request) {
        return service.markDelivered(transactionId, idempotencyKey, request);
    }

    @PostMapping({"/api/v1/transactions/{transactionId}/mark-proof-placeholder",
            "/api/v1/transactions/{transactionId}/mark-purchase-proof-placeholder",
            "/api/v1/transactions/{transactionId}/mark-delivery-proof-placeholder"})
    public TransactionResponse markProof(@PathVariable UUID transactionId,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                         @Valid @RequestBody TransactionActionRequest request) {
        return service.markProof(transactionId, idempotencyKey, request);
    }

    @PostMapping("/api/v1/transactions/{transactionId}/claim-completion")
    public TransactionResponse claimCompletion(@PathVariable UUID transactionId,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                               @Valid @RequestBody TransactionActionRequest request) {
        return service.claimCompletion(transactionId, idempotencyKey, request);
    }

    @PostMapping("/api/v1/transactions/{transactionId}/confirm-completion")
    public TransactionResponse confirmCompletion(@PathVariable UUID transactionId,
                                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                 @Valid @RequestBody TransactionActionRequest request) {
        return service.confirmCompletion(transactionId, idempotencyKey, request);
    }

    @PostMapping("/api/v1/transactions/{transactionId}/cancel")
    public TransactionResponse cancel(@PathVariable UUID transactionId,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                      @Valid @RequestBody TransactionActionRequest request) {
        return service.cancel(transactionId, idempotencyKey, request);
    }

    @PostMapping("/api/v1/transactions/{transactionId}/report-no-show")
    public TransactionResponse reportNoShow(@PathVariable UUID transactionId,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                            @Valid @RequestBody NoShowRequest request) {
        return service.reportNoShow(transactionId, idempotencyKey, request);
    }

    @GetMapping("/api/v1/transactions/{transactionId}/timeline")
    public TransactionTimelineResponse timeline(
            @PathVariable UUID transactionId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset
    ) {
        return service.timeline(transactionId, eventType, from, to, limit, offset);
    }

    @PostMapping("/api/v1/transactions/invariants/verify")
    public TransactionInvariantResponse verify(@RequestBody(required = false) InvariantVerifyRequest request) {
        return service.verify(request == null ? new InvariantVerifyRequest(null) : request);
    }
}
