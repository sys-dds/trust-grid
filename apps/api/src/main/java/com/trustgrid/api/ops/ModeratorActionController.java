package com.trustgrid.api.ops;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ModeratorActionController {
    private final ModeratorActionService service;

    public ModeratorActionController(ModeratorActionService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/ops/moderator-actions/hide-listing")
    public Map<String, Object> hide(@RequestBody Map<String, Object> request) {
        return service.act("HIDE_LISTING", request);
    }

    @PostMapping("/api/v1/ops/moderator-actions/request-evidence")
    public Map<String, Object> evidence(@RequestBody Map<String, Object> request) {
        return service.act("REQUEST_EVIDENCE", request);
    }

    @PostMapping("/api/v1/ops/moderator-actions/request-verification")
    public Map<String, Object> verification(@RequestBody Map<String, Object> request) {
        return service.act("REQUEST_VERIFICATION", request);
    }

    @PostMapping("/api/v1/ops/moderator-actions/restrict-capability")
    public Map<String, Object> restrict(@RequestBody Map<String, Object> request) {
        return service.act("RESTRICT_CAPABILITY", request);
    }

    @PostMapping("/api/v1/ops/moderator-actions/restore-capability")
    public Map<String, Object> restore(@RequestBody Map<String, Object> request) {
        return service.act("RESTORE_CAPABILITY", request);
    }

    @PostMapping("/api/v1/ops/moderator-actions/escalate-dispute")
    public Map<String, Object> escalate(@RequestBody Map<String, Object> request) {
        return service.act("ESCALATE_DISPUTE", request);
    }

    @PostMapping("/api/v1/ops/moderator-actions/suppress-review-weight")
    public Map<String, Object> suppress(@RequestBody Map<String, Object> request) {
        return service.act("SUPPRESS_REVIEW_WEIGHT", request);
    }

    @PostMapping("/api/v1/ops/moderator-actions/restore-review-weight")
    public Map<String, Object> restoreReview(@RequestBody Map<String, Object> request) {
        return service.act("RESTORE_REVIEW_WEIGHT", request);
    }

    @GetMapping("/api/v1/ops/moderator-actions")
    public List<Map<String, Object>> actions() {
        return service.actions();
    }
}
