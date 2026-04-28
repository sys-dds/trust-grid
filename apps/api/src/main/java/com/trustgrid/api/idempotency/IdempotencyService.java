package com.trustgrid.api.idempotency;

import com.trustgrid.api.shared.ConflictException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final CanonicalRequestHashService hashService;

    public IdempotencyService(IdempotencyRepository repository, CanonicalRequestHashService hashService) {
        this.repository = repository;
        this.hashService = hashService;
    }

    public <T> T run(String scope, String key, Map<String, Object> path, Object request, String resourceType,
                     Function<UUID, T> existingResponse, Supplier<UUID> mutation) {
        requireKey(key);
        String hash = hashService.hash(scope, path, request);
        return repository.find(scope, key)
                .map(existing -> {
                    if (!existing.requestHash().equals(hash)) {
                        throw new ConflictException("Idempotency key was already used with a different request");
                    }
                    return existingResponse.apply(existing.resourceId());
                })
                .orElseGet(() -> {
                    UUID resourceId = mutation.get();
                    repository.insert(scope, key, hash, resourceType, resourceId);
                    return existingResponse.apply(resourceId);
                });
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
    }
}
