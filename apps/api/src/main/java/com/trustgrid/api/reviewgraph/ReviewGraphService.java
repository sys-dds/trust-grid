package com.trustgrid.api.reviewgraph;

import com.trustgrid.api.reputation.RecalculateReputationRequest;
import com.trustgrid.api.reputation.ReputationService;
import com.trustgrid.api.shared.OutboxRepository;
import java.sql.Array;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
                UUID id = repository.createCluster("RECIPROCAL_REVIEWS", reviews.size() > 2 ? "HIGH" : "MEDIUM",
                        summary, detector.policySignals("RECIPROCAL_REVIEWS"), List.of(a, b), reviews);
                outboxRepository.insert("REVIEW_ABUSE_CLUSTER", id, null, "REVIEW_ABUSE_CLUSTER_DETECTED", Map.of("clusterType", "RECIPROCAL_REVIEWS"));
                created++;
            }
        }
        created += groupedCluster("LOW_VALUE_REVIEW_FARMING", "MEDIUM", """
                select reviewed_participant_id::text as member, array_agg(review_id::text) as reviews
                from review_graph_edges where transaction_value_cents < 1000 group by reviewed_participant_id having count(*) >= 1
                """);
        created += groupedCluster("SIMILAR_REVIEW_TEXT", "HIGH", """
                select normalized_text_hash as member, array_agg(review_id::text) as reviews
                from review_graph_edges where normalized_text_hash is not null group by normalized_text_hash having count(*) >= 2
                """);
        created += groupedCluster("REVIEW_BURST", "MEDIUM", """
                select reviewed_participant_id::text as member, array_agg(review_id::text) as reviews
                from review_graph_edges where created_at > now() - interval '1 day' group by reviewed_participant_id having count(*) >= 2
                """);
        List<ReviewGraphEdgeResponse> edges = repository.edges();
        if (edges.stream().map(ReviewGraphEdgeResponse::reviewerParticipantId).distinct().count() >= 3
                && !repository.clusterExists("REVIEW_RING", "review-ring:deterministic")) {
            List<UUID> members = edges.stream().flatMap(edge -> List.of(edge.reviewerParticipantId(), edge.reviewedParticipantId()).stream())
                    .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toCollection(LinkedHashSet::new), ArrayList::new));
            List<UUID> reviews = edges.stream().map(ReviewGraphEdgeResponse::reviewId).limit(10).toList();
            UUID id = repository.createCluster("REVIEW_RING", "HIGH", "review-ring:deterministic",
                    detector.policySignals("REVIEW_RING"), members, reviews);
            outboxRepository.insert("REVIEW_ABUSE_CLUSTER", id, null, "REVIEW_ABUSE_CLUSTER_DETECTED", Map.of("clusterType", "REVIEW_RING"));
            created++;
        }
        return created;
    }

    private int groupedCluster(String type, String severity, String sql) {
        int created = 0;
        for (Map<String, Object> row : repository.grouped(sql)) {
            String summary = type.toLowerCase() + ":" + row.get("member");
            if (!repository.clusterExists(type, summary)) {
                List<UUID> reviews = uuidArray(row.get("reviews"));
                UUID id = repository.createCluster(type, severity, summary, detector.policySignals(type), List.of(), reviews);
                outboxRepository.insert("REVIEW_ABUSE_CLUSTER", id, null, "REVIEW_ABUSE_CLUSTER_DETECTED", Map.of("clusterType", type));
                created++;
            }
        }
        return created;
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

    private String require(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }
}
