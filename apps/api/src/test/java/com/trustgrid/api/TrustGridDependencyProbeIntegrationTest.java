package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrustGridDependencyProbeIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("trustgrid")
            .withUsername("trustgrid")
            .withPassword("trustgrid");

    @LocalServerPort
    int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
        registry.add("spring.data.redis.port", () -> "1");
        registry.add("trustgrid.opensearch-url", () -> "http://localhost:1");
        registry.add("trustgrid.minio-endpoint", () -> "http://localhost:1");
    }

    @Test
    void dependencyFailuresReturnStructuredResponseWithoutCrashing() {
        var response = restTemplate.getForEntity(url("/api/v1/system/dependencies"), Map.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsKeys("service", "timestamp", "dependencies");
        assertThat((List<?>) response.getBody().get("dependencies")).hasSize(5);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
