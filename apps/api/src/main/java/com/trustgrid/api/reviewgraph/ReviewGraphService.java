package com.trustgrid.api.reviewgraph;

import com.trustgrid.api.reputation.RecalculateReputationRequest;
import com.trustgrid.api.reputation.ReputationService;
import com.trustgrid.api.shared.OutboxRepository;
import java.sql.Array;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewGraphService {
    private final ReviewGraphRepository repository;
    private final ReviewAbuseDetector detector;
    private final ReputationService reputationService;
    private final OutboxRepository outboxRepository;

    public ReviewGraphService(ReviewGraphRepository repository, ReviewAbuseDetector detector,
                              ReputationService reputationService, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.detector = detector;
        this.reputationService = reputationService;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Map<String, Object> rebuild() {
        int edges = repository.rebuildEdges();
        repository.edges().forEach(edge -> outboxRepository.insert("REVIEW", edge.reviewId(), edge.reviewerParticipantId(),
                "REVIEW_GRAPH_EDGE_CREATED", Map.of("reviewedParticipantId", edge.reviewedParticipantId())));
        int clusters = detectClusters();
        return Map.of("edgesCreated", edges, "clustersCreated", clusters, "policyVersion", "review_abuse_rules_v1");
    }

    public List<ReviewAbuseClusterResponse> clusters(String status) {
        return repository.clusters(status);
    }

    public ReviewAbuseClusterResponse cluster(UUID clusterId) {
        return repository.cluster(clusterId);
    }

    @Transactional
    public Map<String, Object> suppress(UUID clusterId, Map<String, Object> request) {
        ReviewAbuseClusterResponse cluster = cluster(clusterId);
        String actor = require(request, "actor");
        String reason = require(request, "reason");
        int suppressed = repository.suppressReviews(clusterId, cluster.reviewIds(), actor, reason);
        for (UUID member : cluster.memberParticipantIds()) {
            reputationService.recalculate(member, new RecalculateReputationRequest(actor, "Review graph suppression"));
        }
        outboxRepository.insert("REVIEW_ABUSE_CLUSTER", clusterId, null, "REVIEW_WEIGHT_SUPPRESSED",
                Map.of("reviewCount", suppressed, "actor", actor));
        return Map.of("clusterId", clusterId, "suppressedReviews", suppressed, "status", "SUPPRESSED");
    }

    public TrustGraphRiskResponse trustGraphRisk(UUID participantId) {
        List<ReviewAbuseClusterResponse> clusters = repository.clustersForParticipant(participantId);
        List<String> signals = clusters.stream().map(ReviewAbuseClusterResponse::clusterType).distinct().toList();
        return new TrustGraphRiskResponse(participantId, clusters.size(), clusters, signals, "review_abuse_rules_v1");
    }

    private int detectClusters() {
        int created = 0;
        for (Map<String, Object> pair : repository.reciprocalPairs()) {
            UUID a = (UUID) pair.get("a_id");
            UUID b = (UUID) pair.get("b_id");
            List<UUID> reviews = uuidArray(pair.get("reviews"));
            String summary = "reciprocal:" + a + ":" + b;
            if (!repository.clusterExists("RECIPROCAL_REVIEWS", summary)) {
                boolean repeated = reviews.size() >= 4;
                UUID id = repository.createCluster("RECIPROCAL_REVIEWS", repeated ? "HIGH" : "LOW",
                        summary, detector.policySignals("reciprocal_reviews", repeated ? 4 : 2, reviews.size(),
                                List.of(a, b), reviews, repeated ? "repeated reciprocal pair" : "single reciprocal pair"), List.of(a, b), reviews);
                outboxRepository.insert("REVIEW_ABUSE_CLUSTER", id, null, "REVIEW_ABUSE_CLUSTER_DETECTED", Map.of("clusterType", "RECIPROCAL_REVIEWS"));
                created++;
            }
        }
        created += groupedCluster("LOW_VALUE_REVIEW_FARMING", "HIGH", 3, "reviewed participant has repeated low-value reviews", """
                select reviewed_participant_id::text as member, array_agg(review_id::text) as reviews,
                       array_agg(distinct reviewer_participant_id::text) || array_agg(distinct reviewed_participant_id::text) as participants
                from review_graph_edges
                where transaction_value_cents < 1000 and created_at > now() - interval '30 days'
                group by reviewed_participant_id having count(*) >= 3
                """);
        created += groupedCluster("SIMILAR_REVIEW_TEXT", "HIGH", 3, "same normalized text hash repeated", """
                select normalized_text_hash as member, array_agg(review_id::text) as reviews,
                       array_agg(distinct reviewer_participant_id::text) || array_agg(distinct reviewed_participant_id::text) as participants
                from review_graph_edges
                where normalized_text_hash is not null and normalized_text_hash <> md5('')
                group by normalized_text_hash having count(*) >= 3
                """);
        created += groupedCluster("REVIEW_BURST", "MEDIUM", 3, "review count reached burst window threshold", """
                select reviewed_participant_id::text as member, array_agg(review_id::text) as reviews,
                       array_agg(distinct reviewer_participant_id::text) || array_agg(distinct reviewed_participant_id::text) as participants
                from review_graph_edges where created_at > now() - interval '1 day'
                group by reviewed_participant_id having count(*) >= 3
                """);
        RingCandidate ring = ringCandidate(repository.edges());
        String ringSummary = ring == null ? null : "review-ring:" + ring.members();
        if (ring != null && !repository.clusterExists("REVIEW_RING", ringSummary)) {
            UUID id = repository.createCluster("REVIEW_RING", "HIGH", ringSummary,
                    detector.policySignals("review_ring", 3, ring.reviews().size(), ring.members(), ring.reviews(),
                            "three-participant cycle or dense mutual reviews"), ring.members(), ring.reviews());
            outboxRepository.insert("REVIEW_ABUSE_CLUSTER", id, null, "REVIEW_ABUSE_CLUSTER_DETECTED", Map.of("clusterType", "REVIEW_RING"));
            created++;
        }
        return created;
    }

    private int groupedCluster(String type, String severity, int threshold, String severityReason, String sql) {
        int created = 0;
        for (Map<String, Object> row : repository.grouped(sql)) {
            String summary = type.toLowerCase() + ":" + row.get("member");
            if (!repository.clusterExists(type, summary)) {
                List<UUID> reviews = uuidArray(row.get("reviews"));
                List<UUID> participants = uuidArray(row.get("participants"));
                UUID id = repository.createCluster(type, severity, summary,
                        detector.policySignals(type.toLowerCase(), threshold, reviews.size(), participants, reviews, severityReason),
                        participants, reviews);
                outboxRepository.insert("REVIEW_ABUSE_CLUSTER", id, null, "REVIEW_ABUSE_CLUSTER_DETECTED", Map.of("clusterType", type));
                created++;
            }
        }
        return created;
    }

    private RingCandidate ringCandidate(List<ReviewGraphEdgeResponse> edges) {
        Map<String, UUID> reviewByDirectedPair = new java.util.LinkedHashMap<>();
        Set<String> directedPairs = new HashSet<>();
        for (ReviewGraphEdgeResponse edge : edges) {
            String pair = edge.reviewerParticipantId() + ">" + edge.reviewedParticipantId();
            directedPairs.add(pair);
            reviewByDirectedPair.putIfAbsent(pair, edge.reviewId());
        }
        List<UUID> participants = edges.stream()
                .flatMap(edge -> List.of(edge.reviewerParticipantId(), edge.reviewedParticipantId()).stream())
                .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toCollection(LinkedHashSet::new), ArrayList::new));
        for (UUID a : participants) {
            for (UUID b : participants) {
                for (UUID c : participants) {
                    if (a.equals(b) || b.equals(c) || a.equals(c)) {
                        continue;
                    }
                    String ab = a + ">" + b;
                    String bc = b + ">" + c;
                    String ca = c + ">" + a;
                    if (directedPairs.contains(ab) && directedPairs.contains(bc) && directedPairs.contains(ca)) {
                        return new RingCandidate(List.of(a, b, c),
                                List.of(reviewByDirectedPair.get(ab), reviewByDirectedPair.get(bc), reviewByDirectedPair.get(ca)));
                    }
                }
            }
        }
        return null;
    }

    private List<UUID> uuidArray(Object value) {
        if (value instanceof Array array) {
            try {
                Object[] raw = (Object[]) array.getArray();
                List<UUID> ids = new ArrayList<>();
                for (Object item : raw) {
                    ids.add(UUID.fromString(item.toString()));
                }
                return ids;
            } catch (Exception ignored) {
                return List.of();
            }
        }
        return List.of();
    }

    private record RingCandidate(List<UUID> members, List<UUID> reviews) {
    }

    private String require(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }
}
