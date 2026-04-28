package com.trustgrid.api.review;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/transactions/{transactionId}/reviews")
    public ReviewResponse create(@PathVariable UUID transactionId,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                 @Valid @RequestBody CreateReviewRequest request) {
        return service.create(transactionId, idempotencyKey, request);
    }

    @GetMapping("/api/v1/participants/{participantId}/reviews")
    public List<ReviewResponse> participantReviews(@PathVariable UUID participantId) {
        return service.participantReviews(participantId);
    }
}
