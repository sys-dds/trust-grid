package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TrustGridErrorContractIntegrationTest.ValidationProbeTestConfiguration.class)
class TrustGridErrorContractIntegrationTest {

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
    }

    @Test
    void validationErrorReturnsStructuredResponseWithoutStackTrace() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Id", "test-request-id");

        var response = restTemplate.exchange(
                url("/test/validation-probe"),
                HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"\"}", headers),
                Map.class
        );

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody())
                .containsKeys("timestamp", "status", "error", "message", "path", "requestId", "fieldErrors")
                .containsEntry("status", 400)
                .containsEntry("requestId", "test-request-id");
        assertThat(response.toString()).doesNotContain("stackTrace", "java.lang", "exception");
    }

    @Test
    void disabledSeedEndpointReturnsForbiddenContract() {
        var response = restTemplate.postForEntity(url("/api/v1/system/seed/foundation"), null, Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody())
                .containsKeys("timestamp", "status", "error", "message", "path", "requestId", "fieldErrors")
                .containsEntry("status", 403)
                .containsEntry("path", "/api/v1/system/seed/foundation");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @TestConfiguration
    static class ValidationProbeTestConfiguration {

        @RestController
        static class ValidationProbeController {

            @PostMapping("/test/validation-probe")
            ValidationProbeResponse validationProbe(@Valid @RequestBody ValidationProbeRequest request) {
                return new ValidationProbeResponse(request.name());
            }
        }

        record ValidationProbeRequest(@NotBlank String name) {
        }

        record ValidationProbeResponse(String name) {
        }
    }
}
