package com.trustgrid.api.paymentboundary;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentBoundaryController {
    private final PaymentBoundaryService service;

    public PaymentBoundaryController(PaymentBoundaryService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/transactions/{transactionId}/payment-boundary")
    public Map<String, Object> get(@PathVariable UUID transactionId) {
        return service.get(transactionId);
    }

    @PostMapping("/api/v1/transactions/{transactionId}/payment-boundary/request-release")
    public Map<String, Object> release(@PathVariable UUID transactionId, @RequestBody Map<String, Object> request) {
        return service.release(transactionId, request);
    }

    @PostMapping("/api/v1/transactions/{transactionId}/payment-boundary/request-refund")
    public Map<String, Object> refund(@PathVariable UUID transactionId, @RequestBody Map<String, Object> request) {
        return service.refund(transactionId, request);
    }

    @PostMapping("/api/v1/transactions/{transactionId}/payment-boundary/request-payout-hold")
    public Map<String, Object> hold(@PathVariable UUID transactionId, @RequestBody Map<String, Object> request) {
        return service.hold(transactionId, request);
    }

    @PostMapping("/api/v1/transactions/{transactionId}/payment-boundary/close")
    public Map<String, Object> close(@PathVariable UUID transactionId, @RequestBody Map<String, Object> request) {
        return service.close(transactionId, request);
    }
}
