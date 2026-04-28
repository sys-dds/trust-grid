package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class ParticipantIntegrationTestSupport {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("trustgrid")
            .withUsername("trustgrid")
            .withPassword("trustgrid");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbcTemplate;

    TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("trustgrid.seed.endpoint-enabled", () -> "false");
        registry.add("management.health.redis.enabled", () -> "false");
    }

    @BeforeEach
    void setUpRestTemplate() {
        restTemplate = new TestRestTemplate();
    }

    Map<?, ?> createParticipant(String slug, String displayName, String idempotencyKey) {
        ResponseEntity<Map> response = post("/api/v1/participants", Map.of(
                "profileSlug", slug,
                "displayName", displayName,
                "createdBy", "operator@example.com",
                "reason", "Initial marketplace onboarding"
        ), idempotencyKey);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }

    ResponseEntity<Map> post(String path, Object body, String idempotencyKey) {
        return exchange(path, HttpMethod.POST, body, idempotencyKey);
    }

    ResponseEntity<Map> patch(String path, Object body, String idempotencyKey) {
        return exchange(path, HttpMethod.PATCH, body, idempotencyKey);
    }

    ResponseEntity<Map> get(String path) {
        return restTemplate.getForEntity(url(path), Map.class);
    }

    ResponseEntity<Map> exchange(String path, HttpMethod method, Object body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return restTemplate.exchange(url(path), method, new HttpEntity<>(body, headers), Map.class);
    }

    UUID participantId(Map<?, ?> responseBody) {
        return UUID.fromString((String) responseBody.get("participantId"));
    }

    @SuppressWarnings("unchecked")
    List<Object> list(Map<?, ?> responseBody, String key) {
        return (List<Object>) responseBody.get(key);
    }

    @SuppressWarnings("unchecked")
    Map<Object, Object> childMap(Map<?, ?> responseBody, String key) {
        return (Map<Object, Object>) responseBody.get(key);
    }

    Integer countRows(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }
}
