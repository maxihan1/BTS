// V011 마이그레이션 검증 — oidc_provider_configs 테이블/컬럼/제약/인덱스 + OIDC authn_providers seed (FR-AU-04)

package com.atlas.bts.identity.provider.oidc

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

/**
 * Flyway V001~V011 마이그레이션 자동 적용 후 oidc_provider_configs 테이블/제약/시드를 검증한다 (FR-AU-04 OIDC SSO).
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * authn_provider_id 는 authn_providers(id) FK — JIT 자동 프로비저닝(AutoProvisionService)이
 * providerId 로 authn_providers.id 를 요구하므로(C9), V011 이 OIDC authn_providers seed row 도 INSERT 한다.
 */
@Testcontainers
class OidcProviderConfigsSchemaTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
    }

    private fun tableExists(tableName: String): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    private fun columnExists(
        tableName: String,
        columnName: String,
    ): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    private fun columnIsNotNull(
        tableName: String,
        columnName: String,
    ): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getString(1) == "NO"
                }
            }
        }
    }

    private fun columnType(
        tableName: String,
        columnName: String,
    ): String {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT data_type FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getString(1)
                }
            }
        }
    }

    private fun uniqueConstraintOnColumn(
        tableName: String,
        column: String,
    ): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*) FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'UNIQUE'
                  AND kcu.column_name = ?
                """,
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, column)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    private fun fkExistsToTable(
        fromTable: String,
        toTable: String,
        fromColumn: String,
    ): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*)
                FROM information_schema.referential_constraints rc
                JOIN information_schema.table_constraints tc
                    ON rc.constraint_name = tc.constraint_name
                JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_name = ?
                  AND kcu.column_name = ?
                  AND rc.unique_constraint_name IN (
                      SELECT constraint_name FROM information_schema.table_constraints
                      WHERE table_name = ? AND constraint_type = 'PRIMARY KEY'
                  )
                """,
            ).use { stmt ->
                stmt.setString(1, fromTable)
                stmt.setString(2, fromColumn)
                stmt.setString(3, toTable)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    private fun indexExists(indexName: String): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    private fun oidcAuthnProviderSeedCount(): Int {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM authn_providers WHERE type = 'OIDC'",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    // OIDC seed 의 고정 UUID — SAML(...-4a03-...-003)과 반드시 다른 값이어야 한다 (C4).
    private fun oidcSeedHasFixedUuid(uuid: String): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM authn_providers WHERE type = 'OIDC' AND id = ?::uuid",
            ).use { stmt ->
                stmt.setString(1, uuid)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    @Test
    fun `V011 creates oidc_provider_configs table`() {
        assertThat(tableExists("oidc_provider_configs")).isTrue()
    }

    @Test
    fun `V011 oidc_provider_configs has all expected columns`() {
        assertThat(columnExists("oidc_provider_configs", "id")).isTrue()
        assertThat(columnExists("oidc_provider_configs", "registration_id")).isTrue()
        assertThat(columnExists("oidc_provider_configs", "display_name")).isTrue()
        assertThat(columnExists("oidc_provider_configs", "issuer_uri")).isTrue()
        assertThat(columnExists("oidc_provider_configs", "client_id")).isTrue()
        assertThat(columnExists("oidc_provider_configs", "client_secret_encrypted")).isTrue()
        assertThat(columnExists("oidc_provider_configs", "scopes")).isTrue()
        assertThat(columnExists("oidc_provider_configs", "authn_provider_id")).isTrue()
        assertThat(columnExists("oidc_provider_configs", "enabled")).isTrue()
        assertThat(columnExists("oidc_provider_configs", "created_at")).isTrue()
        assertThat(columnExists("oidc_provider_configs", "updated_at")).isTrue()
    }

    @Test
    fun `V011 required columns are NOT NULL`() {
        assertThat(columnIsNotNull("oidc_provider_configs", "registration_id")).isTrue()
        assertThat(columnIsNotNull("oidc_provider_configs", "display_name")).isTrue()
        assertThat(columnIsNotNull("oidc_provider_configs", "issuer_uri")).isTrue()
        assertThat(columnIsNotNull("oidc_provider_configs", "client_id")).isTrue()
        assertThat(columnIsNotNull("oidc_provider_configs", "client_secret_encrypted")).isTrue()
        assertThat(columnIsNotNull("oidc_provider_configs", "scopes")).isTrue()
        assertThat(columnIsNotNull("oidc_provider_configs", "authn_provider_id")).isTrue()
        assertThat(columnIsNotNull("oidc_provider_configs", "enabled")).isTrue()
    }

    @Test
    fun `V011 timestamps are timestamptz`() {
        assertThat(columnType("oidc_provider_configs", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnType("oidc_provider_configs", "updated_at")).isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V011 UNIQUE constraint on registration_id`() {
        assertThat(uniqueConstraintOnColumn("oidc_provider_configs", "registration_id")).isTrue()
    }

    @Test
    fun `V011 authn_provider_id FK references authn_providers`() {
        assertThat(fkExistsToTable("oidc_provider_configs", "authn_providers", "authn_provider_id")).isTrue()
    }

    @Test
    fun `V011 enabled partial index exists`() {
        assertThat(indexExists("idx_oidc_provider_configs_enabled")).isTrue()
    }

    @Test
    fun `V011 seeds an OIDC authn_providers row for JIT provisioning`() {
        assertThat(oidcAuthnProviderSeedCount()).isEqualTo(1)
    }

    @Test
    fun `V011 OIDC seed uses FR-AU-04 fixed UUID distinct from SAML`() {
        assertThat(oidcSeedHasFixedUuid("00000000-0000-4a04-8000-000000000004")).isTrue()
    }
}
