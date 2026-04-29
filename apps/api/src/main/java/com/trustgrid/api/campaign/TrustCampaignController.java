package com.trustgrid.api.campaign;

import com.trustgrid.api.trustsafety.TrustSafetyService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrustCampaignController {
    private final TrustSafetyService service;

    public TrustCampaignController(TrustSafetyService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/trust-campaigns")
    Map<String, Object> create(@RequestBody Map<String, Object> request) { return service.createCampaign(request); }

    @GetMapping("/api/v1/trust-campaigns")
    List<Map<String, Object>> list() { return service.campaigns(); }

    @GetMapping("/api/v1/trust-campaigns/{campaignId}")
    Map<String, Object> get(@PathVariable UUID campaignId) { return service.campaign(campaignId); }

    @PostMapping("/api/v1/trust-campaigns/{campaignId}/graph/rebuild")
    Map<String, Object> rebuild(@PathVariable UUID campaignId) { return service.rebuildCampaignGraph(campaignId); }

    @GetMapping("/api/v1/trust-campaigns/{campaignId}/graph")
    List<Map<String, Object>> graph(@PathVariable UUID campaignId) { return service.campaignGraph(campaignId); }

    @GetMapping("/api/v1/trust-campaigns/{campaignId}/blast-radius")
    Map<String, Object> blastRadius(@PathVariable UUID campaignId) { return service.campaignBlastRadius(campaignId); }

    @PostMapping("/api/v1/trust-campaigns/{campaignId}/containment/simulate")
    Map<String, Object> simulate(@PathVariable UUID campaignId, @RequestBody Map<String, Object> request) {
        return service.simulateContainment(campaignId, request);
    }

    @PostMapping("/api/v1/trust-campaigns/{campaignId}/containment/plans")
    Map<String, Object> plan(@PathVariable UUID campaignId, @RequestBody Map<String, Object> request) {
        return service.createContainmentPlan(campaignId, request);
    }

    @PostMapping("/api/v1/trust-campaigns/containment-plans/{planId}/approve")
    Map<String, Object> approve(@PathVariable UUID planId, @RequestBody Map<String, Object> request) {
        return service.approveContainment(planId, request);
    }

    @PostMapping("/api/v1/trust-campaigns/containment-plans/{planId}/execute")
    Map<String, Object> execute(@PathVariable UUID planId, @RequestBody(required = false) Map<String, Object> request) {
        return service.executeContainment(planId, request == null ? Map.of() : request);
    }

    @PostMapping("/api/v1/trust-campaigns/containment-plans/{planId}/reverse")
    Map<String, Object> reverse(@PathVariable UUID planId, @RequestBody(required = false) Map<String, Object> request) {
        return service.reverseContainment(planId, request == null ? Map.of() : request);
    }

    @GetMapping("/api/v1/trust-campaigns/{campaignId}/timeline")
    List<Map<String, Object>> timeline(@PathVariable UUID campaignId) { return service.campaignTimeline(campaignId); }
}
