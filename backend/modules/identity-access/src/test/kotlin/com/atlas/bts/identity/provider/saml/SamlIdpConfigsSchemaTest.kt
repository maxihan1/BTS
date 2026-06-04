// V010 마이그레이션 검증 — saml_idp_configs 테이블/컬럼/제약/인덱스 + SAML authn_providers seed (FR-AU-03)

package com.atlas.bts.identity.provider.saml

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

/**
 * Flyway V001~V010 마이그레이션 자동 적용 후 saml_idp_configs 테이블/제약/시드를 검증한다 (FR-AU-03 SAML SSO).
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * authn_provider_id 는 authn_providers(id) FK — JIT 자동 프로비저닝(AutoProvisionService)이
 * providerId 로 authn_providers.id 를 요구하므로(C9), V010 이 SAML authn_providers seed row 도 INSERT 한다.
 */
@Testcontainers
class SamlIdpConfigsSchemaTest {
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

    private fun samlAuthnProviderSeedCount(): Int {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM authn_providers WHERE type = 'SAML'",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    @Test
    fun `V010 creates saml_idp_configs table`() {
        assertThat(tableExists("saml_idp_configs")).isTrue()
    }

    @Test
    fun `V010 saml_idp_configs has all expected columns`() {
        assertThat(columnExists("saml_idp_configs", "id")).isTrue()
        assertThat(columnExists("saml_idp_configs", "registration_id")).isTrue()
        assertThat(columnExists("saml_idp_configs", "display_name")).isTrue()
        assertThat(columnExists("saml_idp_configs", "idp_entity_id")).isTrue()
        assertThat(columnExists("saml_idp_configs", "idp_sso_url")).isTrue()
        assertThat(columnExists("saml_idp_configs", "idp_x509_cert")).isTrue()
        assertThat(columnExists("saml_idp_configs", "authn_provider_id")).isTrue()
        assertThat(columnExists("saml_idp_configs", "enabled")).isTrue()
        assertThat(columnExists("saml_idp_configs", "created_at")).isTrue()
        assertThat(columnExists("saml_idp_configs", "updated_at")).isTrue()
    }

    @Test
    fun `V010 required columns are NOT NULL`() {
        assertThat(columnIsNotNull("saml_idp_configs", "registration_id")).isTrue()
        assertThat(columnIsNotNull("saml_idp_configs", "display_name")).isTrue()
        assertThat(columnIsNotNull("saml_idp_configs", "idp_entity_id")).isTrue()
        assertThat(columnIsNotNull("saml_idp_configs", "idp_sso_url")).isTrue()
        assertThat(columnIsNotNull("saml_idp_configs", "idp_x509_cert")).isTrue()
        assertThat(columnIsNotNull("saml_idp_configs", "authn_provider_id")).isTrue()
        assertThat(columnIsNotNull("saml_idp_configs", "enabled")).isTrue()
    }

    @Test
    fun `V010 timestamps are timestamptz`() {
        assertThat(columnType("saml_idp_configs", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnType("saml_idp_configs", "updated_at")).isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V010 UNIQUE constraint on registration_id`() {
        assertThat(uniqueConstraintOnColumn("saml_idp_configs", "registration_id")).isTrue()
    }

    @Test
    fun `V010 authn_provider_id FK references authn_providers`() {
        assertThat(fkExistsToTable("saml_idp_configs", "authn_providers", "authn_provider_id")).isTrue()
    }

    @Test
    fun `V010 enabled partial index exists`() {
        assertThat(indexExists("idx_saml_idp_configs_enabled")).isTrue()
    }

    @Test
    fun `V010 seeds a SAML authn_providers row for JIT provisioning`() {
        assertThat(samlAuthnProviderSeedCount()).isEqualTo(1)
    }
}
