// issue-tracking V015 마이그레이션 검증 — custom_field_definitions/custom_field_options 테이블 + issues.custom_fields JSONB + GIN 인덱스 + 부분 유니크 + FK CASCADE + TIMESTAMPTZ 존재 확인

package com.bts.issue.customfield

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * Flyway V001~V015 마이그레이션 적용 후 커스텀 필드 스키마를 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 검증 범위 (FR-IS-10).
 * - custom_field_definitions 테이블 존재
 * - custom_field_options 테이블 존재
 * - issues.custom_fields JSONB NOT NULL DEFAULT '{}' 컬럼 존재
 * - GIN 인덱스 idx_issues_custom_fields 존재
 * - 부분 유니크 인덱스 ux_custom_field_definitions_project_key_active 존재 — (project_id, key) WHERE deleted_at IS NULL
 * - FK 인덱스 idx_custom_field_definitions_project_id / idx_custom_field_options_field_id 존재 (DATA.md §7)
 * - created_at / updated_at / deleted_at 이 TIMESTAMPTZ (DATA.md §4)
 * - 활성 기준 (project_id, key) 유일 — 같은 활성 key INSERT 시 유니크 위반
 * - 소프트 삭제 후 동명 key 재생성 허용 — 부분 유니크가 활성만 커버
 * - 옵션 UNIQUE (field_id, value) 위반 검증
 * - 옵션 FK ON DELETE CASCADE — 정의 삭제 시 옵션 자동 정리
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 *
 * 참조. FR-IS-10 plan Task 2 / DATA.md §3 소프트 삭제 / §4 TIMESTAMPTZ / §7 FK 인덱스.
 */
@Testcontainers
class CustomFieldsMigrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V002 pgmq 확장 요구로 인해 tembo 이미지 사용.
        // asCompatibleSubstituteFor("postgres"): Testcontainers 이미지 호환성 검증 우회.
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .load()
                .migrate()
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun tableExists(tableName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables" +
                    " WHERE table_schema = 'public' AND table_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    private fun indexExists(indexName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM pg_indexes" +
                    " WHERE schemaname = 'public' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // GIN 인덱스 여부까지 확인 — pg_indexes.indexdef 에 'USING gin' 포함 검사.
    private fun indexUsesGin(indexName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1).lowercase().contains("using gin") else false
                }
            }
        }

    // Connection → prepareStatement → executeQuery 3중 use 블록 중첩. SQL 헬퍼의 관용적 패턴이므로 Suppress.
    @Suppress("NestedBlockDepth")
    private fun columnDataType(
        tableName: String,
        columnName: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT data_type FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    @Suppress("NestedBlockDepth")
    private fun columnIsNotNull(
        tableName: String,
        columnName: String,
    ): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) == "NO" else false }
            }
        }

    // 활성 projects row 1건 생성 후 id 반환 — FK 충족용.
    private fun insertProject(): UUID =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) RETURNING id",
            ).use { stmt ->
                // 테스트마다 유니크 key 필요 — 무작위 숫자 접미사 사용.
                val key = "P" + UUID.randomUUID().toString().filter { it.isDigit() }.take(4).ifEmpty { "1" }
                stmt.setString(1, key.uppercase())
                stmt.setString(2, "테스트 프로젝트")
                stmt.executeQuery().use { rs ->
                    rs.next()
                    UUID.fromString(rs.getString(1))
                }
            }
        }

    @Suppress("NestedBlockDepth")
    private fun insertDefinition(
        projectId: UUID,
        key: String,
        deleted: Boolean,
    ): UUID =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val sql =
                if (deleted) {
                    "INSERT INTO custom_field_definitions" +
                        " (project_id, key, name, field_type, display_order, deleted_at)" +
                        " VALUES (?, ?, ?, 'SHORT_TEXT', 1, NOW()) RETURNING id"
                } else {
                    "INSERT INTO custom_field_definitions" +
                        " (project_id, key, name, field_type, display_order)" +
                        " VALUES (?, ?, ?, 'SHORT_TEXT', 1) RETURNING id"
                }
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, key)
                stmt.setString(3, "필드 $key")
                stmt.executeQuery().use { rs ->
                    rs.next()
                    UUID.fromString(rs.getString(1))
                }
            }
        }

    private fun insertOption(
        fieldId: UUID,
        value: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO custom_field_options (field_id, value, label, display_order)" +
                    " VALUES (?, ?, ?, 1)",
            ).use { stmt ->
                stmt.setObject(1, fieldId)
                stmt.setString(2, value)
                stmt.setString(3, "라벨 $value")
                stmt.executeUpdate()
            }
        }
    }

    private fun deleteDefinitionHard(fieldId: UUID) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("DELETE FROM custom_field_definitions WHERE id = ?").use { stmt ->
                stmt.setObject(1, fieldId)
                stmt.executeUpdate()
            }
        }
    }

    private fun countOptions(fieldId: UUID): Int =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM custom_field_options WHERE field_id = ?",
            ).use { stmt ->
                stmt.setObject(1, fieldId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    // ── 테이블/컬럼/인덱스 존재 검증 ──────────────────────────────────────────

    @Test
    fun `V015 custom_field_definitions 테이블 존재`() {
        assertThat(tableExists("custom_field_definitions")).isTrue()
    }

    @Test
    fun `V015 custom_field_options 테이블 존재`() {
        assertThat(tableExists("custom_field_options")).isTrue()
    }

    @Test
    fun `V015 issues custom_fields 컬럼은 jsonb`() {
        assertThat(columnDataType("issues", "custom_fields")).isEqualTo("jsonb")
    }

    @Test
    fun `V015 issues custom_fields 는 NOT NULL`() {
        assertThat(columnIsNotNull("issues", "custom_fields")).isTrue()
    }

    @Test
    fun `V015 GIN 인덱스 idx_issues_custom_fields 존재`() {
        assertThat(indexExists("idx_issues_custom_fields")).isTrue()
        assertThat(indexUsesGin("idx_issues_custom_fields")).isTrue()
    }

    @Test
    fun `V015 부분 유니크 인덱스 ux_custom_field_definitions_project_key_active 존재`() {
        assertThat(indexExists("ux_custom_field_definitions_project_key_active")).isTrue()
    }

    @Test
    fun `V015 FK 인덱스 idx_custom_field_definitions_project_id 존재`() {
        assertThat(indexExists("idx_custom_field_definitions_project_id")).isTrue()
    }

    @Test
    fun `V015 FK 인덱스 idx_custom_field_options_field_id 존재`() {
        assertThat(indexExists("idx_custom_field_options_field_id")).isTrue()
    }

    // ── TIMESTAMPTZ 타입 검증 (DATA.md §4) ───────────────────────────────────

    @Test
    fun `V015 custom_field_definitions created_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("custom_field_definitions", "created_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V015 custom_field_definitions updated_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("custom_field_definitions", "updated_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V015 custom_field_definitions deleted_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("custom_field_definitions", "deleted_at"))
            .isEqualTo("timestamp with time zone")
    }

    // ── 부분 유니크 동작 검증 ─────────────────────────────────────────────────

    @Test
    fun `V015 같은 프로젝트 활성 동일 key 정의는 유니크 위반`() {
        val projectId = insertProject()
        insertDefinition(projectId, "salary_impact", deleted = false)
        assertThatThrownBy { insertDefinition(projectId, "salary_impact", deleted = false) }
            .hasMessageContaining("ux_custom_field_definitions_project_key_active")
    }

    @Test
    fun `V015 소프트 삭제 후 동일 key 재생성 허용`() {
        val projectId = insertProject()
        insertDefinition(projectId, "customer_name", deleted = true)
        // 삭제된 row 가 있어도 동일 key 활성 row INSERT 는 성공해야 한다 (부분 인덱스가 활성만 커버).
        insertDefinition(projectId, "customer_name", deleted = false)
    }

    // ── 옵션 UNIQUE / FK CASCADE 검증 ─────────────────────────────────────────

    @Test
    fun `V015 옵션 field_id value 중복은 유니크 위반`() {
        val projectId = insertProject()
        val fieldId = insertDefinition(projectId, "priority_select", deleted = false)
        insertOption(fieldId, "high")
        assertThatThrownBy { insertOption(fieldId, "high") }
            .hasMessageContaining("custom_field_options")
    }

    @Test
    fun `V015 정의 삭제 시 옵션 ON DELETE CASCADE`() {
        val projectId = insertProject()
        val fieldId = insertDefinition(projectId, "cascade_select", deleted = false)
        insertOption(fieldId, "a")
        insertOption(fieldId, "b")
        assertThat(countOptions(fieldId)).isEqualTo(2)
        deleteDefinitionHard(fieldId)
        assertThat(countOptions(fieldId)).isEqualTo(0)
    }
}
