package com.trustgrid.api.listing;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/listings")
public class ListingController {

    private final ListingService service;

    public ListingController(ListingService service) {
        this.service = service;
    }

    @PostMapping
    public ListingResponse create(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                  @Valid @RequestBody CreateListingDraftRequest request) {
        return service.create(idempotencyKey, request);
    }

    @GetMapping("/{listingId}")
    public ListingResponse get(@PathVariable UUID listingId) {
        return service.get(listingId);
    }

    @PatchMapping("/{listingId}")
    public ListingResponse update(@PathVariable UUID listingId,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                  @Valid @RequestBody UpdateListingDraftRequest request) {
        return service.update(listingId, idempotencyKey, request);
    }

    @PostMapping("/{listingId}/submit")
    public ListingResponse submit(@PathVariable UUID listingId,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                  @Valid @RequestBody ListingActionRequest request) {
        return service.submit(listingId, idempotencyKey, request);
    }

    @PostMapping("/{listingId}/publish")
    public ListingResponse publish(@PathVariable UUID listingId,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                   @Valid @RequestBody ListingActionRequest request) {
        return service.publish(listingId, idempotencyKey, request);
    }

    @GetMapping("/{listingId}/evidence-requirements")
    public List<EvidenceRequirementResponse> evidence(@PathVariable UUID listingId) {
        return service.evidence(listingId);
    }

    @PostMapping("/{listingId}/evidence-requirements/{requirementId}/mark-satisfied")
    public EvidenceRequirementResponse markEvidenceSatisfied(@PathVariable UUID listingId,
                                                             @PathVariable UUID requirementId,
                                                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                             @Valid @RequestBody ListingActionRequest request) {
        return service.markEvidenceSatisfied(listingId, requirementId, idempotencyKey, request);
    }

    @PostMapping("/{listingId}/moderation/{action}")
    public ListingResponse moderate(@PathVariable UUID listingId,
                                    @PathVariable String action,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    @Valid @RequestBody ListingActionRequest request) {
        return service.moderate(listingId, action, idempotencyKey, request);
    }

    @GetMapping("/{listingId}/events")
    public List<ListingEventResponse> events(@PathVariable UUID listingId) {
        return service.events(listingId);
    }

}
