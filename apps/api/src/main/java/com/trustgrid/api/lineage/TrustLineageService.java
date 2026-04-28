package com.trustgrid.api.lineage;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrustLineageService {
    private final TrustLineageRepository repository;
    private final OutboxRepository outboxRepository;

    public TrustLineageService(TrustLineageRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Map<String, Object> rebuild(String type, Map<String, Object> request) {
        UUID id = repository.rebuild(type, request);
        outboxRepository.insert("LINEAGE_REBUILD", id, null, "LINEAGE_REBUILD_RUN", Map.of("rebuildType", type));
        if (type.equals("TRUST_SCORE_LINEAGE") || type.equals("FULL_LINEAGE")) {
            outboxRepository.insert("LINEAGE_REBUILD", id, null, "TRUST_SCORE_LINEAGE_RECORDED", Map.of("rebuildType", type));
        }
        if (type.equals("RANKING_LINEAGE") || type.equals("FULL_LINEAGE")) {
            outboxRepository.insert("LINEAGE_REBUILD", id, null, "RANKING_LINEAGE_RECORDED", Map.of("rebuildType", type));
        }
        if (type.equals("POLICY_LINEAGE") || type.equals("FULL_LINEAGE")) {
            outboxRepository.insert("LINEAGE_REBUILD", id, null, "POLICY_LINEAGE_RECORDED", Map.of("rebuildType", type));
        }
        return Map.of("rebuildRunId", id, "rebuildType", type, "status", "SUCCEEDED");
    }

    public Map<String, Object> trustExplanation(UUID participantId) {
        return repository.trustExplanation(participantId);
    }

    public List<Map<String, Object>> trustLineage(UUID participantId) {
        return repository.trustLineage(participantId);
    }

    public List<Map<String, Object>> rankingLineage(UUID listingId) {
        return repository.rankingLineage(listingId);
    }

    public List<Map<String, Object>> policyLineage(String policyName) {
        return repository.policyLineage(policyName);
    }

    public List<Map<String, Object>> rebuildRuns() {
        return repository.rebuildRuns();
    }
}
