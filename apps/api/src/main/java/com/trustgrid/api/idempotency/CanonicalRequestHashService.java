package com.trustgrid.api.idempotency;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CanonicalRequestHashService {

    private final ObjectMapper objectMapper;

    public CanonicalRequestHashService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String hash(String scope, Map<String, Object> path, Object request) {
        try {
            String canonical = objectMapper.writeValueAsString(Map.of(
                    "scope", scope,
                    "path", path == null ? Map.of() : path,
                    "request", request == null ? Map.of() : request
            ));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Could not calculate idempotency request hash", exception);
        }
    }
}
