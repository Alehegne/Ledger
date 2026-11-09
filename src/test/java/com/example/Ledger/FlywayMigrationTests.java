package com.example.Ledger;

import com.example.Ledger.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FlywayMigrationTests extends PostgresIntegrationTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationV1IsAppliedSuccessfully() {
        Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success = true",
                Integer.class
        );
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void migrationV4IsAppliedSuccessfully() {
        Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '4' and success = true",
                Integer.class
        );
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void optimisticLockingVersionColumnExistsOnAllCoreTables() {
        List<String> expectedTables = List.of(
                "organization",
                "app_user",
                "account",
                "journal_entry",
                "journal_line",
                "audit_log",
                "invitation",
                "refresh_token"
        );

        expectedTables.forEach(table -> {
            Integer found = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.columns where table_schema = 'public' and table_name = ? and column_name = 'version'",
                    Integer.class,
                    table
            );
            assertThat(found)
                    .as("table %s should have version column", table)
                    .isEqualTo(1);
        });
    }

    @Test
    void dayOneCoreTablesExist() {
        List<String> expectedTables = List.of(
                "organization",
                "app_user",
                "account",
                "journal_entry",
                "journal_line",
                "audit_log",
                "invitation",
                "refresh_token"
        );

        expectedTables.forEach(table -> {
            Integer found = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.tables where table_schema = 'public' and table_name = ?",
                    Integer.class,
                    table
            );
            assertThat(found)
                    .as("table %s should exist", table)
                    .isEqualTo(1);
        });
    }

    @Test
    void migrationV10AndPlatformGovernanceSchemaExist() {
        Integer appliedV10 = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '10' and success = true",
                Integer.class
        );
        assertThat(appliedV10).isEqualTo(1);

        Integer platformAuditTable = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name = 'platform_audit_log'",
                Integer.class
        );
        assertThat(platformAuditTable).isEqualTo(1);

        Integer orgNameUniqueIndex = jdbcTemplate.queryForObject(
                "select count(*) from pg_indexes where schemaname = 'public' and tablename = 'organization' and indexname = 'uk_organization_name_lower'",
                Integer.class
        );
        assertThat(orgNameUniqueIndex).isEqualTo(1);

        Integer openInviteUniqueIndex = jdbcTemplate.queryForObject(
                "select count(*) from pg_indexes where schemaname = 'public' and tablename = 'invitation' and indexname = 'uk_invitation_open_org_email'",
                Integer.class
        );
        assertThat(openInviteUniqueIndex).isEqualTo(1);

        Integer kindColumn = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'organization' and column_name = 'kind'",
                Integer.class
        );
        assertThat(kindColumn).isEqualTo(1);

        Integer userStatusColumn = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'app_user' and column_name = 'status'",
                Integer.class
        );
        assertThat(userStatusColumn).isEqualTo(1);
    }
}