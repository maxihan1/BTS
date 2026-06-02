// issue-tracking V009 마이그레이션 검증 — components 테이블 + 부분 유니크 인덱스 + FK 인덱스 + TIMESTAMPTZ 존재 확인

package com.bts.issue.component.migration

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
 * Flyway V001~V009 마이그레이션 적용 후 components 테이블을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 검증 범위.
 * - components 테이블 존재
 * - 부분 유니크 인덱스 ux_components_project_id_name_active 존재 — (project_id, name) WHERE deleted_at IS NULL
 * - FK 인덱스 idx_components_project_id 존재 (DATA.md §7)
 * - created_at / updated_at / deleted_at 이 TIMESTAMPTZ (DATA.md §4)
 * - 활성 기준 (project_id, name) 유일 — 같은 활성 이름 INSERT 시 유니크 위반
 * - 소프트 삭제 후 동명 재생성 허용 — deleted_at 채운 row 와 동명 활성 row 공존 가능
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 *
 * 참조. FR-CM-01 plan Task 2 / DATA.md §3 소프트 삭제 / §7 FK 인덱스.
 */
@Testcontainers
class ComponentsMigrationTest {
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

    // 활성 projects row 1건 생성 후 id 반환 — FK 충족용.
    private fun insertProject(): UUID =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) RETURNING id",
            ).use { stmt ->
                // 테스트마다 유니크 key 필요 — 무작위 대문자 접두사 사용.
                val key = "P" + UUID.randomUUID().toString().filter { it.isDigit() }.take(4).ifEmpty { "1" }
                stmt.setString(1, key.uppercase())
                stmt.setString(2, "테스트 프로젝트")
                stmt.executeQuery().use { rs ->
                    rs.next()
                    UUID.fromString(rs.getString(1))
                }
            }
        }

    private fun insertComponent(
        projectId: UUID,
        name: String,
        deleted: Boolean,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val sql =
                if (deleted) {
                    "INSERT INTO components (project_id, name, deleted_at) VALUES (?, ?, NOW())"
                } else {
                    "INSERT INTO components (project_id, name) VALUES (?, ?)"
                }
            conn.prepareStatement(sql).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.executeUpdate()
            }
        }
    }

    // ── 테이블/인덱스 존재 검증 ────────────────────────────────────────────────

    @Test
    fun `V009 components 테이블 존재`() {
        assertThat(tableExists("components")).isTrue()
    }

    @Test
    fun `V009 부분 유니크 인덱스 ux_components_project_id_name_active 존재`() {
        assertThat(indexExists("ux_components_project_id_name_active")).isTrue()
    }

    @Test
    fun `V009 FK 인덱스 idx_components_project_id 존재`() {
        assertThat(indexExists("idx_components_project_id")).isTrue()
    }

    // ── TIMESTAMPTZ 타입 검증 (DATA.md §4) ───────────────────────────────────

    @Test
    fun `V009 components created_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("components", "created_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V009 components updated_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("components", "updated_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V009 components deleted_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("components", "deleted_at"))
            .isEqualTo("timestamp with time zone")
    }

    // ── 부분 유니크 동작 검증 ─────────────────────────────────────────────────

    @Test
    fun `V009 같은 프로젝트 활성 동명 컴포넌트는 유니크 위반`() {
        val projectId = insertProject()
        insertComponent(projectId, "백엔드", deleted = false)
        assertThatThrownBy { insertComponent(projectId, "백엔드", deleted = false) }
            .hasMessageContaining("ux_components_project_id_name_active")
    }

    @Test
    fun `V009 소프트 삭제 후 동명 재생성 허용`() {
        val projectId = insertProject()
        insertComponent(projectId, "프론트엔드", deleted = true)
        // 삭제된 row 가 있어도 동명 활성 row INSERT 는 성공해야 한다 (부분 인덱스가 활성만 커버).
        insertComponent(projectId, "프론트엔드", deleted = false)
    }
}
