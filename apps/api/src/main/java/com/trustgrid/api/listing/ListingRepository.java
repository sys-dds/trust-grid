package com.trustgrid.api.listing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trustgrid.api.category.CategoryResponse;
import com.trustgrid.api.category.CategoryRiskTier;
import com.trustgrid.api.category.ListingType;
import com.trustgrid.api.participant.ParticipantAccountStatus;
import com.trustgrid.api.participant.VerificationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ListingRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ListingRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    Optional<ParticipantListingView> participant(UUID participantId) {
        return jdbcTemplate.query("""
                select p.id, p.account_status, p.verification_status, p.trust_tier,
                       coalesce(tp.max_transaction_value_cents, 0) as max_transaction_value_cents
                from participants p
                left join trust_profiles tp on tp.participant_id = p.id
                where p.id = ?
                """, (rs, rowNum) -> new ParticipantListingView(
                rs.getObject("id", UUID.class),
                ParticipantAccountStatus.valueOf(rs.getString("account_status")),
                VerificationStatus.valueOf(rs.getString("verification_status")),
                rs.getString("trust_tier"),
                rs.getLong("max_transaction_value_cents")
        ), participantId).stream().findFirst();
    }

    boolean hasCapability(UUID participantId, String capability) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from participant_capabilities
                where participant_id = ? and capability = ? and status = 'ACTIVE'
                """, Integer.class, participantId, capability);
        return count != null && count > 0;
    }

    boolean hasActiveRestriction(UUID participantId, String restrictionType) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from participant_restrictions
                where participant_id = ? and restriction_type = ? and status = 'ACTIVE'
                """, Integer.class, participantId, restrictionType);
        return count != null && count > 0;
    }

    UUID insertListing(CreateListingDraftRequest request, CategoryResponse category) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into marketplace_listings (
                    id, owner_participant_id, listing_type, category_id, title, description,
                    price_amount_cents, budget_amount_cents, currency, location_mode, status,
                    risk_tier, moderation_status, single_accept
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, 'NOT_REVIEWED', ?)
                """,
                id,
                request.ownerParticipantId(),
                request.listingType().name(),
                category.id(),
                request.title(),
                request.description(),
                request.priceAmountCents(),
                request.budgetAmountCents(),
                request.currency() == null || request.currency().isBlank() ? "GBP" : request.currency(),
                request.locationMode().name(),
                category.defaultRiskTier().name(),
                request.singleAccept() == null || request.singleAccept()
        );
        insertDetails(id, request);
        insertRevision(id, 1, request.createdBy(), request.reason(), Map.of("created", true, "title", request.title()));
        return id;
    }

    void updateListing(UUID listingId, UpdateListingDraftRequest request, boolean highRiskLiveEdit) {
        ListingResponse current = find(listingId).orElseThrow();
        int nextRevision = current.revision() + 1;
        jdbcTemplate.update("""
                update marketplace_listings
                set title = ?, description = ?, price_amount_cents = ?, budget_amount_cents = ?,
                    location_mode = ?, revision = ?, status = case when ? then 'UNDER_REVIEW' else status end,
                    moderation_status = case when ? then 'NEEDS_REVIEW' else moderation_status end,
                    updated_at = now()
                where id = ?
                """,
                request.title(),
                request.description(),
                request.priceAmountCents(),
                request.budgetAmountCents(),
                request.locationMode() == null ? current.locationMode().name() : request.locationMode().name(),
                nextRevision,
                highRiskLiveEdit,
                highRiskLiveEdit,
                listingId
        );
        insertRevision(listingId, nextRevision, request.updatedBy(), request.reason(), Map.of(
                "title", request.title(),
                "description", request.description(),
                "priceAmountCents", request.priceAmountCents(),
                "budgetAmountCents", request.budgetAmountCents()
        ));
    }

    void submit(UUID listingId) {
        jdbcTemplate.update("""
                update marketplace_listings
                set status = 'PENDING_RISK_CHECK', submitted_at = now(), updated_at = now()
                where id = ? and status = 'DRAFT'
                """, listingId);
    }

    void setPublishOutcome(UUID listingId, ListingStatus status, ListingModerationStatus moderationStatus) {
        jdbcTemplate.update("""
                update marketplace_listings
                set status = ?, moderation_status = ?, published_at = case when ? = 'LIVE' then now() else published_at end,
                    hidden_at = case when ? = 'HIDDEN' then now() else hidden_at end, updated_at = now()
                where id = ?
                """, status.name(), moderationStatus.name(), status.name(), status.name(), listingId);
    }

    void moderate(UUID listingId, ListingStatus status, ListingModerationStatus moderationStatus) {
        jdbcTemplate.update("""
                update marketplace_listings
                set status = ?, moderation_status = ?,
                    hidden_at = case when ? = 'HIDDEN' then now() else hidden_at end,
                    rejected_at = case when ? = 'REJECTED' then now() else rejected_at end,
                    expired_at = case when ? = 'EXPIRED' then now() else expired_at end,
                    updated_at = now()
                where id = ?
                """, status.name(), moderationStatus.name(), status.name(), status.name(), status.name(), listingId);
    }

    Optional<ListingResponse> find(UUID listingId) {
        return jdbcTemplate.query(baseSql() + " where l.id = ?", this::listingRow, listingId).stream().findFirst();
    }

    List<EvidenceRequirementResponse> evidence(UUID listingId) {
        return jdbcTemplate.query("""
                select id, listing_id, evidence_type, required_before_publish, satisfied, reason, created_at, updated_at
                from listing_evidence_requirements
                where listing_id = ?
                order by created_at
                """, this::evidenceRow, listingId);
    }

    void markEvidenceSatisfied(UUID requirementId) {
        jdbcTemplate.update("""
                update listing_evidence_requirements
                set satisfied = true, updated_at = now()
                where id = ?
                """, requirementId);
    }

    boolean hasUnsatisfiedRequiredEvidence(UUID listingId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from listing_evidence_requirements
                where listing_id = ? and required_before_publish = true and satisfied = false
                """, Integer.class, listingId);
        return count != null && count > 0;
    }

    UUID insertRiskSnapshot(UUID listingId, ListingRiskTier tier, String decision, List<String> rules, String actor, Map<String, Object> snapshot) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into listing_risk_snapshots (id, listing_id, risk_tier, decision, matched_rules_json, actor, snapshot_json)
                values (?, ?, ?, ?, cast(? as jsonb), ?, cast(? as jsonb))
                """, id, listingId, tier.name(), decision, json(rules), actor, json(snapshot));
        return id;
    }

    UUID insertDuplicateFinding(UUID listingId, String type, String severity, List<UUID> matches, String reason) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into duplicate_listing_findings (id, listing_id, finding_type, severity, status, matched_listing_ids_json, reason)
                values (?, ?, ?, ?, 'OPEN', cast(? as jsonb), ?)
                """, id, listingId, type, severity, json(matches), reason);
        return id;
    }

    List<UUID> sameOwnerTitlePrice(UUID listingId, UUID ownerId, String title, Long price, Long budget) {
        return jdbcTemplate.query("""
                select id from marketplace_listings
                where id <> ? and owner_participant_id = ? and lower(title) = lower(?)
                  and coalesce(price_amount_cents, -1) = coalesce(?, -1)
                  and coalesce(budget_amount_cents, -1) = coalesce(?, -1)
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), listingId, ownerId, title, price, budget);
    }

    int highValueCategoryCount(UUID ownerId, UUID categoryId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from marketplace_listings
                where owner_participant_id = ? and category_id = ? and coalesce(price_amount_cents, 0) >= 50000
                """, Integer.class, ownerId, categoryId);
        return count == null ? 0 : count;
    }

    void upsertSearchDocument(UUID listingId, boolean searchable, String backendStatus) {
        ListingResponse listing = find(listingId).orElseThrow();
        jdbcTemplate.update("""
                insert into listing_search_documents (
                    listing_id, owner_participant_id, listing_type, category_code, title, description,
                    price_amount_cents, budget_amount_cents, location_mode, status, risk_tier,
                    searchable, search_backend_status, indexed_at, document_json, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), cast(? as jsonb), now())
                on conflict (listing_id) do update set
                    title = excluded.title,
                    description = excluded.description,
                    price_amount_cents = excluded.price_amount_cents,
                    budget_amount_cents = excluded.budget_amount_cents,
                    location_mode = excluded.location_mode,
                    status = excluded.status,
                    risk_tier = excluded.risk_tier,
                    searchable = excluded.searchable,
                    search_backend_status = excluded.search_backend_status,
                    indexed_at = excluded.indexed_at,
                    document_json = excluded.document_json,
                    updated_at = now()
                """,
                listing.listingId(),
                listing.ownerParticipantId(),
                listing.listingType().name(),
                listing.categoryCode(),
                listing.title(),
                listing.description(),
                listing.priceAmountCents(),
                listing.budgetAmountCents(),
                listing.locationMode().name(),
                listing.status().name(),
                listing.riskTier().name(),
                searchable,
                backendStatus,
                json(Map.of("title", listing.title(), "categoryCode", listing.categoryCode(), "listingType", listing.listingType().name()))
        );
    }

    List<ListingResponse> search(String query, ListingType listingType, String categoryCode, LocationMode locationMode,
                                 Long minPrice, Long maxPrice, ListingRiskTier riskTier, boolean trustedOnly,
                                 int limit, int offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(baseSql() + """
                join listing_search_documents sd on sd.listing_id = l.id
                join participants p on p.id = l.owner_participant_id
                left join participant_restrictions pr on pr.participant_id = p.id
                    and pr.status = 'ACTIVE' and pr.restriction_type in ('HIDDEN_FROM_MARKETPLACE_SEARCH', 'LISTING_BLOCKED')
                where sd.searchable = true and l.status = 'LIVE'
                  and p.account_status not in ('SUSPENDED', 'CLOSED', 'RESTRICTED')
                  and pr.id is null
                """);
        if (query != null && !query.isBlank()) {
            sql.append(" and (lower(l.title) like ? or lower(l.description) like ?) ");
            args.add("%" + query.toLowerCase() + "%");
            args.add("%" + query.toLowerCase() + "%");
        }
        if (listingType != null) {
            sql.append(" and l.listing_type = ? ");
            args.add(listingType.name());
        }
        if (categoryCode != null && !categoryCode.isBlank()) {
            sql.append(" and c.code = ? ");
            args.add(categoryCode);
        }
        if (locationMode != null) {
            sql.append(" and l.location_mode = ? ");
            args.add(locationMode.name());
        }
        if (minPrice != null) {
            sql.append(" and coalesce(l.price_amount_cents, l.budget_amount_cents, 0) >= ? ");
            args.add(minPrice);
        }
        if (maxPrice != null) {
            sql.append(" and coalesce(l.price_amount_cents, l.budget_amount_cents, 0) <= ? ");
            args.add(maxPrice);
        }
        if (riskTier != null) {
            sql.append(" and l.risk_tier = ? ");
            args.add(riskTier.name());
        }
        if (trustedOnly) {
            sql.append(" and p.trust_tier in ('TRUSTED', 'HIGH_TRUST') ");
        }
        sql.append(" order by l.published_at desc nulls last, l.created_at desc limit ? offset ? ");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), this::listingRow, args.toArray());
    }

    void insertEvent(String aggregateType, UUID aggregateId, UUID participantId, String eventType, Map<String, Object> payload) {
        jdbcTemplate.update("""
                insert into marketplace_events (
                    id, aggregate_type, aggregate_id, participant_id, event_key, event_type, payload_json
                ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """,
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                participantId,
                eventType + ":" + aggregateId + ":" + UUID.randomUUID(),
                eventType,
                json(payload)
        );
    }

    List<ListingEventResponse> events(UUID listingId) {
        return jdbcTemplate.query("""
                select id, event_key, event_type, event_status, publish_attempts, published_at, payload_json, created_at
                from marketplace_events
                where aggregate_type = 'LISTING' and aggregate_id = ?
                order by created_at
                """, (rs, rowNum) -> new ListingEventResponse(
                rs.getObject("id", UUID.class),
                rs.getString("event_key"),
                rs.getString("event_type"),
                rs.getString("event_status"),
                rs.getInt("publish_attempts"),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(),
                readMap(rs.getString("payload_json")),
                rs.getTimestamp("created_at").toInstant()
        ), listingId);
    }

    private void insertDetails(UUID listingId, CreateListingDraftRequest request) {
        Map<String, Object> details = request.details() == null ? Map.of() : request.details();
        switch (request.listingType()) {
            case SERVICE_OFFER -> {
                Object pricingModel = details.getOrDefault("pricingModel", "FIXED");
                boolean remote = bool(details.get("remoteAllowed"));
                boolean inPerson = bool(details.get("inPersonAllowed"));
                jdbcTemplate.update("""
                        insert into listing_service_details (
                            listing_id, pricing_model, remote_allowed, in_person_allowed, service_duration_minutes,
                            trial_allowed, cancellation_policy, availability_summary
                        ) values (?, ?, ?, ?, ?, ?, ?, ?)
                        """, listingId, pricingModel, remote, inPerson, integer(details.get("serviceDurationMinutes")),
                        bool(details.get("trialAllowed")), string(details.get("cancellationPolicy")), string(details.get("availabilitySummary")));
            }
            case ITEM_LISTING -> {
                boolean highValue = bool(details.get("highValue")) || (request.priceAmountCents() != null && request.priceAmountCents() >= 50000);
                jdbcTemplate.update("""
                        insert into listing_item_details (
                            listing_id, item_condition, brand, high_value, shipping_allowed, local_pickup_allowed,
                            proof_photo_required, ownership_proof_required
                        ) values (?, ?, ?, ?, ?, ?, ?, ?)
                        """, listingId, details.getOrDefault("itemCondition", "GOOD"), string(details.get("brand")),
                        highValue, bool(details.get("shippingAllowed")), !details.containsKey("localPickupAllowed") || bool(details.get("localPickupAllowed")),
                        highValue, highValue);
                if (highValue) {
                    insertEvidence(listingId, "LISTING_PHOTO", true, "High-value item photo placeholder required");
                    insertEvidence(listingId, "OWNERSHIP_PROOF", true, "High-value item ownership proof placeholder required");
                }
            }
            case ERRAND_REQUEST -> {
                jdbcTemplate.update("""
                        insert into listing_errand_details (
                            listing_id, pickup_summary, dropoff_summary, deadline_at, proof_required, local_only, safety_category
                        ) values (?, ?, ?, ?, ?, ?, ?)
                        """, listingId, string(details.get("pickupSummary")), string(details.get("dropoffSummary")),
                        timestamp(details.get("deadlineAt")), bool(details.get("proofRequired")),
                        !details.containsKey("localOnly") || bool(details.get("localOnly")), string(details.get("safetyCategory")));
                if (bool(details.get("proofRequired"))) {
                    insertEvidence(listingId, "DELIVERY_PROOF_PLACEHOLDER", false, "Errand proof placeholder requested");
                }
            }
            case SHOPPING_REQUEST -> {
                Long buyerBudget = longValue(details.get("buyerBudgetCents"));
                Long reward = longValue(details.get("shopperRewardCents"));
                jdbcTemplate.update("""
                        insert into listing_shopping_request_details (
                            listing_id, target_item_description, target_shop_source, buyer_budget_cents, shopper_reward_cents,
                            receipt_required, delivery_proof_required
                        ) values (?, ?, ?, ?, ?, ?, ?)
                        """, listingId, string(details.get("targetItemDescription")), string(details.get("targetShopSource")),
                        buyerBudget == null ? 0 : buyerBudget, reward == null ? 0 : reward,
                        !details.containsKey("receiptRequired") || bool(details.get("receiptRequired")),
                        !details.containsKey("deliveryProofRequired") || bool(details.get("deliveryProofRequired")));
                insertEvidence(listingId, "RECEIPT_PLACEHOLDER", false, "Shopping receipt placeholder required");
                insertEvidence(listingId, "DELIVERY_PROOF_PLACEHOLDER", false, "Shopping delivery proof placeholder required");
            }
        }
    }

    private void insertEvidence(UUID listingId, String type, boolean requiredBeforePublish, String reason) {
        jdbcTemplate.update("""
                insert into listing_evidence_requirements (
                    id, listing_id, evidence_type, required_before_publish, satisfied, reason
                ) values (?, ?, ?, ?, false, ?)
                """, UUID.randomUUID(), listingId, type, requiredBeforePublish, reason);
    }

    private void insertRevision(UUID listingId, int revision, String actor, String reason, Map<String, Object> snapshot) {
        jdbcTemplate.update("""
                insert into listing_revisions (id, listing_id, revision, changed_by, reason, snapshot_json)
                values (?, ?, ?, ?, ?, cast(? as jsonb))
                """, UUID.randomUUID(), listingId, revision, actor, reason, json(snapshot));
    }

    private String baseSql() {
        return """
                select l.id, l.owner_participant_id, l.listing_type, c.code as category_code, l.title, l.description,
                       l.price_amount_cents, l.budget_amount_cents, l.currency, l.location_mode, l.status,
                       l.risk_tier, l.moderation_status, l.single_accept, l.revision, l.created_at, l.updated_at,
                       l.published_at, l.metadata_json
                from marketplace_listings l
                join marketplace_categories c on c.id = l.category_id
                """;
    }

    private ListingResponse listingRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new ListingResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_participant_id", UUID.class),
                ListingType.valueOf(rs.getString("listing_type")),
                rs.getString("category_code"),
                rs.getString("title"),
                rs.getString("description"),
                (Long) rs.getObject("price_amount_cents"),
                (Long) rs.getObject("budget_amount_cents"),
                rs.getString("currency"),
                LocationMode.valueOf(rs.getString("location_mode")),
                ListingStatus.valueOf(rs.getString("status")),
                ListingRiskTier.valueOf(rs.getString("risk_tier")),
                ListingModerationStatus.valueOf(rs.getString("moderation_status")),
                rs.getBoolean("single_accept"),
                rs.getInt("revision"),
                readMap(rs.getString("metadata_json")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                publishedAt == null ? null : publishedAt.toInstant()
        );
    }

    private EvidenceRequirementResponse evidenceRow(ResultSet rs, int rowNum) throws SQLException {
        return new EvidenceRequirementResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("listing_id", UUID.class),
                rs.getString("evidence_type"),
                rs.getBoolean("required_before_publish"),
                rs.getBoolean("satisfied"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }

    Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private boolean bool(Object value) {
        return value instanceof Boolean booleanValue && booleanValue;
    }

    private String string(Object value) {
        return value == null ? null : value.toString();
    }

    private Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Timestamp timestamp(Object value) {
        if (value == null) {
            return null;
        }
        return Timestamp.from(Instant.parse(value.toString()));
    }

    record ParticipantListingView(
            UUID participantId,
            ParticipantAccountStatus accountStatus,
            VerificationStatus verificationStatus,
            String trustTier,
            long maxTransactionValueCents
    ) {
    }
}
