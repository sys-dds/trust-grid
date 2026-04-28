package com.trustgrid.api.listing;

import com.trustgrid.api.category.CategoryResponse;
import com.trustgrid.api.category.CategoryService;
import com.trustgrid.api.category.ListingType;
import com.trustgrid.api.idempotency.IdempotencyService;
import com.trustgrid.api.listing.ListingRepository.ParticipantListingView;
import com.trustgrid.api.participant.ParticipantAccountStatus;
import com.trustgrid.api.participant.VerificationStatus;
import com.trustgrid.api.shared.ConflictException;
import com.trustgrid.api.shared.MarketplaceActionForbiddenException;
import com.trustgrid.api.shared.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListingService {

    private static final long HIGH_VALUE_THRESHOLD_CENTS = 50000;

    private final ListingRepository repository;
    private final CategoryService categoryService;
    private final IdempotencyService idempotencyService;

    public ListingService(ListingRepository repository, CategoryService categoryService, IdempotencyService idempotencyService) {
        this.repository = repository;
        this.categoryService = categoryService;
        this.idempotencyService = idempotencyService;
    }

    @Transactional
    public ListingResponse create(String idempotencyKey, CreateListingDraftRequest request) {
        return idempotencyService.run("listing:create", idempotencyKey, Map.of("ownerParticipantId", request.ownerParticipantId()), request,
                "LISTING", this::get, () -> {
                    CategoryResponse category = categoryService.get(request.categoryCode());
                    if (!category.allowedListingTypes().contains(request.listingType())) {
                        throw new ConflictException("Category does not allow listing type");
                    }
                    validateOwnerCanList(request.ownerParticipantId(), request.listingType());
                    validateCreateDetails(request);
                    UUID listingId = repository.insertListing(request, category);
                    repository.insertEvent("LISTING", listingId, request.ownerParticipantId(), "LISTING_CREATED", Map.of(
                            "listingType", request.listingType().name(),
                            "categoryCode", request.categoryCode(),
                            "actor", request.createdBy(),
                            "reason", request.reason()
                    ));
                    repository.upsertSearchDocument(listingId, false, "POSTGRES_FALLBACK");
                    return listingId;
                });
    }

    public ListingResponse get(UUID listingId) {
        return repository.find(listingId).orElseThrow(() -> new NotFoundException("Listing not found"));
    }

    @Transactional
    public ListingResponse update(UUID listingId, String idempotencyKey, UpdateListingDraftRequest request) {
        return idempotencyService.run("listing:update:" + listingId, idempotencyKey, Map.of("listingId", listingId), request,
                "LISTING", this::get, () -> {
                    ListingResponse listing = get(listingId);
                    boolean highRiskLiveEdit = listing.status() == ListingStatus.LIVE
                            && (listing.riskTier() == ListingRiskTier.HIGH || listing.riskTier() == ListingRiskTier.RESTRICTED
                            || !sameAmount(listing.priceAmountCents(), request.priceAmountCents())
                            || !sameAmount(listing.budgetAmountCents(), request.budgetAmountCents()));
                    repository.updateListing(listingId, request, highRiskLiveEdit);
                    repository.insertEvent("LISTING", listingId, listing.ownerParticipantId(), "LISTING_UPDATED", Map.of(
                            "actor", request.updatedBy(),
                            "reason", request.reason(),
                            "highRiskLiveEdit", highRiskLiveEdit
                    ));
                    repository.upsertSearchDocument(listingId, listing.status() == ListingStatus.LIVE && !highRiskLiveEdit, "POSTGRES_FALLBACK");
                    return listingId;
                });
    }

    @Transactional
    public ListingResponse submit(UUID listingId, String idempotencyKey, ListingActionRequest request) {
        return idempotencyService.run("listing:submit:" + listingId, idempotencyKey, Map.of("listingId", listingId), request,
                "LISTING", this::get, () -> {
                    ListingResponse listing = get(listingId);
                    if (listing.status() != ListingStatus.DRAFT) {
                        throw new ConflictException("Only draft listings can be submitted");
                    }
                    repository.submit(listingId);
                    repository.insertEvent("LISTING", listingId, listing.ownerParticipantId(), "LISTING_SUBMITTED", Map.of(
                            "actor", request.actor(),
                            "reason", request.reason()
                    ));
                    return listingId;
                });
    }

    @Transactional
    public ListingResponse publish(UUID listingId, String idempotencyKey, ListingActionRequest request) {
        return idempotencyService.run("listing:publish:" + listingId, idempotencyKey, Map.of("listingId", listingId), request,
                "LISTING", this::get, () -> {
                    ListingResponse listing = get(listingId);
                    if (listing.status() != ListingStatus.DRAFT && listing.status() != ListingStatus.PENDING_RISK_CHECK) {
                        throw new ConflictException("Listing cannot be published from current status");
                    }
                    List<String> rules = new ArrayList<>();
                    ParticipantListingView owner = validateOwnerCanList(listing.ownerParticipantId(), listing.listingType());
                    detectDuplicates(listing, rules);
                    ListingStatus status = ListingStatus.LIVE;
                    ListingModerationStatus moderationStatus = ListingModerationStatus.AUTO_APPROVED;
                    String decision = "ALLOW_LIVE";
                    if (listing.riskTier() == ListingRiskTier.RESTRICTED) {
                        status = ListingStatus.HIDDEN;
                        moderationStatus = ListingModerationStatus.NEEDS_REVIEW;
                        decision = "HIDE_LISTING";
                        rules.add("CATEGORY_DEFAULT_RISK");
                    } else if (listing.riskTier() == ListingRiskTier.HIGH && owner.verificationStatus() == VerificationStatus.UNVERIFIED) {
                        status = ListingStatus.UNDER_REVIEW;
                        moderationStatus = ListingModerationStatus.NEEDS_REVIEW;
                        decision = "SEND_UNDER_REVIEW";
                        rules.add("HIGH_RISK_CATEGORY_UNVERIFIED_OWNER");
                    }
                    if (repository.hasUnsatisfiedRequiredEvidence(listingId)) {
                        status = ListingStatus.UNDER_REVIEW;
                        moderationStatus = ListingModerationStatus.EVIDENCE_REQUIRED;
                        decision = "REQUIRE_EVIDENCE";
                        rules.add("UNSATISFIED_REQUIRED_EVIDENCE");
                    }
                    if (rules.contains("DUPLICATE_LISTING_PATTERN") && status == ListingStatus.LIVE) {
                        status = ListingStatus.UNDER_REVIEW;
                        moderationStatus = ListingModerationStatus.NEEDS_REVIEW;
                        decision = "SEND_UNDER_REVIEW";
                    }
                    repository.setPublishOutcome(listingId, status, moderationStatus);
                    repository.insertRiskSnapshot(listingId, listing.riskTier(), decision, rules, request.actor(), Map.of(
                            "listingType", listing.listingType().name(),
                            "riskTier", listing.riskTier().name()
                    ));
                    repository.insertEvent("LISTING", listingId, listing.ownerParticipantId(), eventFor(status, moderationStatus), Map.of(
                            "actor", request.actor(),
                            "reason", request.reason(),
                            "decision", decision,
                            "matchedRules", rules
                    ));
                    repository.insertEvent("LISTING", listingId, listing.ownerParticipantId(), "LISTING_RISK_SNAPSHOT_RECORDED", Map.of(
                            "decision", decision,
                            "matchedRules", rules
                    ));
                    repository.upsertSearchDocument(listingId, status == ListingStatus.LIVE, "POSTGRES_FALLBACK");
                    return listingId;
                });
    }

    public List<EvidenceRequirementResponse> evidence(UUID listingId) {
        get(listingId);
        return repository.evidence(listingId);
    }

    @Transactional
    public EvidenceRequirementResponse markEvidenceSatisfied(UUID listingId, UUID requirementId, String idempotencyKey, ListingActionRequest request) {
        return idempotencyService.run("listing:evidence:" + listingId + ":" + requirementId, idempotencyKey,
                Map.of("listingId", listingId, "requirementId", requirementId), request, "LISTING",
                id -> repository.evidence(listingId).stream().filter(item -> item.id().equals(requirementId)).findFirst()
                        .orElseThrow(() -> new NotFoundException("Evidence requirement not found")),
                () -> {
                    ListingResponse listing = get(listingId);
                    repository.markEvidenceSatisfied(requirementId);
                    repository.insertEvent("LISTING", listingId, listing.ownerParticipantId(), "LISTING_UPDATED", Map.of(
                            "evidenceRequirementId", requirementId.toString(),
                            "actor", request.actor(),
                            "reason", request.reason()
                    ));
                    return requirementId;
                });
    }

    @Transactional
    public ListingResponse moderate(UUID listingId, String action, String idempotencyKey, ListingActionRequest request) {
        return idempotencyService.run("listing:moderation:" + action + ":" + listingId, idempotencyKey, Map.of("listingId", listingId), request,
                "LISTING", this::get, () -> {
                    ListingResponse listing = get(listingId);
                    ListingStatus status;
                    ListingModerationStatus moderationStatus;
                    String eventType;
                    switch (action) {
                        case "hide" -> {
                            status = ListingStatus.HIDDEN;
                            moderationStatus = ListingModerationStatus.MODERATOR_HIDDEN;
                            eventType = "LISTING_HIDDEN";
                        }
                        case "reject" -> {
                            status = ListingStatus.REJECTED;
                            moderationStatus = ListingModerationStatus.MODERATOR_REJECTED;
                            eventType = "LISTING_REJECTED";
                        }
                        case "request-evidence" -> {
                            status = ListingStatus.UNDER_REVIEW;
                            moderationStatus = ListingModerationStatus.EVIDENCE_REQUIRED;
                            eventType = "LISTING_EVIDENCE_REQUIRED";
                        }
                        case "restore" -> {
                            validateOwnerCanList(listing.ownerParticipantId(), listing.listingType());
                            if (listing.status() != ListingStatus.HIDDEN) {
                                throw new ConflictException("Only hidden listings can be restored");
                            }
                            status = ListingStatus.LIVE;
                            moderationStatus = ListingModerationStatus.RESTORED;
                            eventType = "LISTING_RESTORED";
                        }
                        case "expire" -> {
                            status = ListingStatus.EXPIRED;
                            moderationStatus = listing.moderationStatus();
                            eventType = "LISTING_EXPIRED";
                        }
                        default -> throw new IllegalArgumentException("Unknown moderation action");
                    }
                    repository.moderate(listingId, status, moderationStatus);
                    repository.insertEvent("LISTING", listingId, listing.ownerParticipantId(), eventType, Map.of(
                            "actor", request.actor(),
                            "reason", request.reason()
                    ));
                    repository.upsertSearchDocument(listingId, status == ListingStatus.LIVE, "POSTGRES_FALLBACK");
                    return listingId;
                });
    }

    public List<ListingEventResponse> events(UUID listingId) {
        get(listingId);
        return repository.events(listingId);
    }

    public List<ListingResponse> search(String query, ListingType listingType, String categoryCode, LocationMode locationMode,
                                        Long minPrice, Long maxPrice, ListingRiskTier riskTier, Boolean trustedOnly,
                                        Integer limit, Integer offset) {
        return repository.search(query, listingType, categoryCode, locationMode, minPrice, maxPrice, riskTier,
                Boolean.TRUE.equals(trustedOnly), clamp(limit), Math.max(offset == null ? 0 : offset, 0));
    }

    private ParticipantListingView validateOwnerCanList(UUID ownerId, ListingType listingType) {
        ParticipantListingView owner = repository.participant(ownerId).orElseThrow(() -> new NotFoundException("Participant not found"));
        if (owner.accountStatus() == ParticipantAccountStatus.SUSPENDED) {
            throw new MarketplaceActionForbiddenException("Suspended owner cannot publish listings");
        }
        if (owner.accountStatus() == ParticipantAccountStatus.CLOSED) {
            throw new MarketplaceActionForbiddenException("Closed owner cannot publish listings");
        }
        if (repository.hasActiveRestriction(ownerId, "LISTING_BLOCKED")) {
            throw new MarketplaceActionForbiddenException("Owner is blocked from publishing listings");
        }
        String requiredCapability = switch (listingType) {
            case SERVICE_OFFER -> "OFFER_SERVICES";
            case ITEM_LISTING -> "SELL_ITEMS";
            case ERRAND_REQUEST, SHOPPING_REQUEST -> "BUY";
        };
        if (!repository.hasCapability(ownerId, requiredCapability)) {
            throw new MarketplaceActionForbiddenException("Owner is missing required marketplace capability");
        }
        return owner;
    }

    private void validateCreateDetails(CreateListingDraftRequest request) {
        Map<String, Object> details = request.details() == null ? Map.of() : request.details();
        switch (request.listingType()) {
            case SERVICE_OFFER -> {
                if (request.priceAmountCents() == null) {
                    throw new IllegalArgumentException("Service offer price is required");
                }
                boolean remote = Boolean.TRUE.equals(details.get("remoteAllowed"));
                boolean inPerson = Boolean.TRUE.equals(details.get("inPersonAllowed"));
                if (!remote && !inPerson) {
                    throw new IllegalArgumentException("Service offer requires remote or in-person availability");
                }
            }
            case ITEM_LISTING -> {
                if (request.priceAmountCents() == null || details.get("itemCondition") == null) {
                    throw new IllegalArgumentException("Item listing price and condition are required");
                }
            }
            case ERRAND_REQUEST -> {
                if (request.budgetAmountCents() == null) {
                    throw new IllegalArgumentException("Errand request budget is required");
                }
            }
            case SHOPPING_REQUEST -> {
                if (details.get("targetItemDescription") == null || details.get("buyerBudgetCents") == null || details.get("shopperRewardCents") == null) {
                    throw new IllegalArgumentException("Shopping request target, budget, and reward are required");
                }
            }
        }
    }

    private void detectDuplicates(ListingResponse listing, List<String> rules) {
        List<UUID> sameOwner = repository.sameOwnerTitlePrice(
                listing.listingId(), listing.ownerParticipantId(), listing.title(), listing.priceAmountCents(), listing.budgetAmountCents());
        if (!sameOwner.isEmpty()) {
            repository.insertDuplicateFinding(listing.listingId(), "SAME_OWNER_TITLE_PRICE", "MEDIUM", sameOwner, "Same owner, title, and amount");
            repository.insertEvent("LISTING", listing.listingId(), listing.ownerParticipantId(), "DUPLICATE_LISTING_DETECTED", Map.of(
                    "findingType", "SAME_OWNER_TITLE_PRICE",
                    "matchedListingIds", sameOwner
            ));
            rules.add("DUPLICATE_LISTING_PATTERN");
        }
        if (listing.riskTier() == ListingRiskTier.HIGH
                && listing.priceAmountCents() != null
                && listing.priceAmountCents() >= HIGH_VALUE_THRESHOLD_CENTS
                && repository.highValueCategoryCount(listing.ownerParticipantId(), categoryService.get(listing.categoryCode()).id()) > 1) {
            repository.insertDuplicateFinding(listing.listingId(), "REPEATED_HIGH_VALUE_CATEGORY_NEW_USER", "HIGH", List.of(), "Repeated high-value category listing");
            repository.insertEvent("LISTING", listing.listingId(), listing.ownerParticipantId(), "DUPLICATE_LISTING_DETECTED", Map.of(
                    "findingType", "REPEATED_HIGH_VALUE_CATEGORY_NEW_USER"
            ));
            rules.add("DUPLICATE_LISTING_PATTERN");
        }
    }

    private String eventFor(ListingStatus status, ListingModerationStatus moderationStatus) {
        if (status == ListingStatus.LIVE) {
            return "LISTING_PUBLISHED";
        }
        if (status == ListingStatus.HIDDEN) {
            return "LISTING_HIDDEN";
        }
        if (moderationStatus == ListingModerationStatus.EVIDENCE_REQUIRED) {
            return "LISTING_EVIDENCE_REQUIRED";
        }
        return "LISTING_UNDER_REVIEW";
    }

    private boolean sameAmount(Long current, Long next) {
        return current == null ? next == null : current.equals(next);
    }

    private int clamp(Integer limit) {
        return limit == null ? 50 : Math.max(1, Math.min(limit, 100));
    }
}
