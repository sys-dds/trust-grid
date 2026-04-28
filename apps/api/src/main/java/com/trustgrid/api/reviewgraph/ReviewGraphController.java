package com.trustgrid.api.reviewgraph;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReviewGraphController {
    private final ReviewGraphService service;

    public ReviewGraphController(ReviewGraphService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/review-graph/rebuild")
    public Map<String, Object> rebuild() {
        return service.rebuild();
    }

    @GetMapping("/api/v1/review-graph/clusters")
    public List<ReviewAbuseClusterResponse> clusters(@RequestParam(required = false) String status) {
        return service.clusters(status);
    }

    @GetMapping("/api/v1/review-graph/clusters/{clusterId}")
    public ReviewAbuseClusterResponse cluster(@PathVariable UUID clusterId) {
        return service.cluster(clusterId);
    }

    @PostMapping("/api/v1/review-graph/clusters/{clusterId}/suppress-review-weight")
    public Map<String, Object> suppress(@PathVariable UUID clusterId, @RequestBody Map<String, Object> request) {
        return service.suppress(clusterId, request);
    }

    @GetMapping("/api/v1/participants/{participantId}/trust-graph-risk")
    public TrustGraphRiskResponse trustGraphRisk(@PathVariable UUID participantId) {
        return service.trustGraphRisk(participantId);
    }
}
