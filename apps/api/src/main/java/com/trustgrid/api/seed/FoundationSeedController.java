package com.trustgrid.api.seed;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system/seed")
public class FoundationSeedController {

    private final FoundationSeedService foundationSeedService;

    public FoundationSeedController(FoundationSeedService foundationSeedService) {
        this.foundationSeedService = foundationSeedService;
    }

    @PostMapping("/foundation")
    public FoundationSeedResponse seedFoundation() {
        return foundationSeedService.seed();
    }
}
