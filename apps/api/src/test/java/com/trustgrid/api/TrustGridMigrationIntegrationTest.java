package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TrustGridMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("trustgrid")
            .withUsername("trustgrid")
            .withPassword("trustgrid");

    @Test
    void flywayAppliesFoundationSchema() {
        DataSource dataSource = dataSource();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        assertThat(tableExists(jdbcTemplate, "marketplace_participants")).isTrue();
        assertThat(tableExists(jdbcTemplate, "participant_capabilities")).isTrue();
        assertThat(tableExists(jdbcTemplate, "trust_profiles")).isTrue();
        assertThat(tableExists(jdbcTemplate, "marketplace_events")).isTrue();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into marketplace_participants (
                    id, display_name, profile_slug, account_status, verification_status
                ) values ('00000000-0000-0000-0000-000000000101', 'Invalid Participant', 'invalid-participant', 'INVALID', 'BASIC')
                """))
                .hasRootCauseInstanceOf(PSQLException.class);
    }

    private boolean tableExists(JdbcTemplate jdbcTemplate, String tableName) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from information_schema.tables
                where table_schema = 'public' and table_name = ?
                """, Integer.class, tableName);
        return count != null && count == 1;
    }

    private DataSource dataSource() {
        return DataSourceBuilder.create()
                .url(POSTGRES.getJdbcUrl())
                .username(POSTGRES.getUsername())
                .password(POSTGRES.getPassword())
                .build();
    }
}
