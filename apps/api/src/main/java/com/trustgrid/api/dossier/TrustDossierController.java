package com.trustgrid.api.dossier;

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
public class TrustDossierController {
    private final TrustSafetyService service;

    public TrustDossierController(TrustSafetyService service) { this.service = service; }

    @GetMapping("/api/v1/trust-dossiers/participants/{participantId}")
    Map<String, Object> participant(@PathVariable UUID participantId) { return service.participantDossier(participantId); }

    @GetMapping("/api/v1/trust-dossiers/listings/{listingId}")
    Map<String, Object> listing(@PathVariable UUID listingId) { return service.listingDossier(listingId); }

    @GetMapping("/api/v1/trust-dossiers/transactions/{transactionId}")
    Map<String, Object> transaction(@PathVariable UUID transactionId) { return service.transactionDossier(transactionId); }

    @GetMapping("/api/v1/trust-dossiers/disputes/{disputeId}")
    Map<String, Object> dispute(@PathVariable UUID disputeId) { return service.disputeDossier(disputeId); }

    @GetMapping("/api/v1/trust-dossiers/campaigns/{campaignId}")
    Map<String, Object> campaign(@PathVariable UUID campaignId) { return service.campaignDossier(campaignId); }

    @PostMapping("/api/v1/trust-dossiers/snapshots")
    Map<String, Object> snapshot(@RequestBody Map<String, Object> request) { return service.createDossierSnapshot(request); }

    @GetMapping("/api/v1/trust-control-room/aggregate")
    Map<String, Object> aggregate() { return service.controlRoom(); }

    @GetMapping("/api/v1/trust-control-room/marketplace-graph-summary")
    Map<String, Object> graphSummary() { return service.marketplaceGraphSummary(); }

    @PostMapping("/api/v1/trust-scale/seed")
    Map<String, Object> seed(@RequestBody Map<String, Object> request) { return service.scaleSeed(request); }

    @GetMapping("/api/v1/trust-scale/runs")
    List<Map<String, Object>> runs() { return service.scaleRuns(); }
}
