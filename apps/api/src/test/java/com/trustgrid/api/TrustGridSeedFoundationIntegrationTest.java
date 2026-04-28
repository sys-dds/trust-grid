package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrustGridSeedFoundationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("trustgrid")
            .withUsername("trustgrid")
            .withPassword("trustgrid");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void foundationSeedCreatesExpectedDataAndIsIdempotent() {
        var firstResponse = restTemplate.postForEntity(url("/api/v1/system/seed/foundation"), null, Map.class);

        assertThat(firstResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(count("marketplace_participants")).isEqualTo(4);
        assertThat(count("trust_profiles")).isEqualTo(4);
        assertThat(count("participant_capabilities")).isEqualTo(6);
        assertThat(count("marketplace_events")).isGreaterThanOrEqualTo(15);

        var secondResponse = restTemplate.postForEntity(url("/api/v1/system/seed/foundation"), null, Map.class);

        assertThat(secondResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(count("marketplace_participants")).isEqualTo(4);
        assertThat(distinctProfileSlugs()).isEqualTo(4);
        assertThat(secondResponse.getBody()).containsEntry("participantsCreated", 0);
    }

    private Integer count(String tableName) {
        return jdbcTemplate.queryForObject("select count(*) from " + tableName, Integer.class);
    }

    private Integer distinctProfileSlugs() {
        return jdbcTemplate.queryForObject("select count(distinct profile_slug) from marketplace_participants", Integer.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
