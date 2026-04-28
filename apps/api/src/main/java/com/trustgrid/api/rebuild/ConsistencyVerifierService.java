package com.trustgrid.api.rebuild;

import org.springframework.stereotype.Service;

@Service
public class ConsistencyVerifierService {
    public String mode() {
        return "deterministic_verifier_v1";
    }
}
