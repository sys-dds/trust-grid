package com.trustgrid.api.rebuild;

import com.trustgrid.api.reputation.RecalculateReputationRequest;
import com.trustgrid.api.reputation.ReputationService;
import com.trustgrid.api.shared.OutboxRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RebuildService {
    private final RebuildRepository repository;
    private final ReputationService reputationService;
    private final OutboxRepository outboxRepository;

    public RebuildService(RebuildRepository repository, ReputationService reputationService, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.reputationService = reputationService;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Map<String, Object> reputation(Map<String, Object> request) {
        int count = 0;
        for (UUID participant : repository.participants()) {
            reputationService.recalculate(participant, new RecalculateReputationRequest(actor(request), reason(request)));
            count++;
        }
        UUID runId = repository.run("REPUTATION", actor(request), reason(request), Map.of("participants", count));
        outboxRepository.insert("REBUILD", runId, null, "REPUTATION_REBUILD_RUN", Map.of("participants", count));
        return Map.of("rebuildRunId", runId, "participants", count, "status", "SUCCEEDED");
    }

    @Transactional
    public Map<String, Object> searchIndex(Map<String, Object> request) {
        int documents = repository.rebuildSearchDocuments();
        UUID runId = repository.run("SEARCH_INDEX", actor(request), reason(request), Map.of("documents", documents));
        outboxRepository.insert("REBUILD", runId, null, "SEARCH_INDEX_REBUILD_RUN", Map.of("documents", documents));
        return Map.of("rebuildRunId", runId, "documents", documents, "searchBackend", "POSTGRES_FALLBACK");
    }

    @Transactional
    public Map<String, Object> evidence(Map<String, Object> request) {
        int findings = repository.verifyEvidence();
        UUID runId = repository.run("EVIDENCE_CONSISTENCY", actor(request), reason(request), Map.of("findings", findings));
        outboxRepository.insert("REBUILD", runId, null, "EVIDENCE_CONSISTENCY_CHECK_RUN", Map.of("findings", findings));
        return Map.of("rebuildRunId", runId, "findings", findings);
    }

    @Transactional
    public Map<String, Object> outbox(Map<String, Object> request) {
        int replayed = repository.replayOutbox();
        UUID runId = repository.run("OUTBOX_REPLAY", actor(request), reason(request), Map.of("replayed", replayed));
        outboxRepository.insert("REBUILD", runId, null, "OUTBOX_REPLAY_RUN", Map.of("replayed", replayed));
        return Map.of("rebuildRunId", runId, "replayed", replayed, "duplicateSafe", true);
    }

    @Transactional
    public Map<String, Object> timeline(Map<String, Object> request) {
        int findings = repository.replayTimeline();
        UUID runId = repository.run("AUDIT_TIMELINE_REPLAY", actor(request), reason(request), Map.of("findings", findings));
        outboxRepository.insert("REBUILD", runId, null, "AUDIT_TIMELINE_REPLAY_RUN", Map.of("findings", findings));
        return Map.of("rebuildRunId", runId, "findings", findings);
    }

    public Map<String, Object> run(UUID id) {
        return repository.run(id);
    }

    public java.util.List<Map<String, Object>> findings() {
        return repository.findings();
    }

    private String actor(Map<String, Object> request) {
        return request.getOrDefault("actor", request.getOrDefault("startedBy", "system")).toString();
    }

    private String reason(Map<String, Object> request) {
        return request.getOrDefault("reason", "Operator requested rebuild").toString();
    }
}
