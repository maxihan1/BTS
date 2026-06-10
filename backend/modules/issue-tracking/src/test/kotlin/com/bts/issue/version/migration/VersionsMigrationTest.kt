// issue-tracking V010 마이그레이션 검증 — versions 테이블 + 부분 유니크 인덱스 + FK 인덱스 + TIMESTAMPTZ/DATE 존재 확인

package com.bts.issue.version.migration

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
 * Flyway V001~V010 마이그레이션 적용 후 versions 테이블을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 검증 범위.
 * - versions 테이블 존재
 * - 부분 유니크 인덱스 ux_versions_project_id_name_active 존재 — (project_id, name) WHERE deleted_at IS NULL
 * - FK 인덱스 idx_versions_project_id 존재 (DATA.md §7)
 * - created_at / updated_at / deleted_at 이 TIMESTAMPTZ (DATA.md §4)
 * - start_date / release_date 이 DATE
 * - 활성 기준 (project_id, name) 유일 — 같은 활성 이름 INSERT 시 유니크 위반
 * - 소프트 삭제 후 동명 재생성 허용 — deleted_at 채운 row 와 동명 활성 row 공존 가능
 * - (V016) status 컬럼 — DEFAULT 'UNRELEASED', released_at 은 nullable TIMESTAMPTZ
 * - (V016) ck_versions_status CHECK 제약 — 'FOO' 같은 미정의 값 거부
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 *
 * 참조. FR-VR-01 plan Task 2 / DATA.md §3 소프트 삭제 / §7 FK 인덱스.
 */
@Testcontainers
class VersionsMigrationTest {
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

    // 컬럼의 is_nullable ('YES'/'NO') 조회 — released_at 이 nullable 인지 검증용.
    @Suppress("NestedBlockDepth")
    private fun columnIsNullable(
        tableName: String,
        columnName: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    // status 컬럼에 값을 주지 않고 INSERT 한 row 의 status 를 조회 — DEFAULT 'UNRELEASED' 검증용.
    @Suppress("NestedBlockDepth")
    private fun insertVersionAndReadStatus(
        projectId: UUID,
        name: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO versions (project_id, name) VALUES (?, ?) RETURNING status",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    // status 에 임의 문자열을 명시 INSERT — ck_versions_status CHECK 위반 유도용.
    private fun insertVersionWithStatus(
        projectId: UUID,
        name: String,
        status: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO versions (project_id, name, status) VALUES (?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.setString(3, status)
                stmt.executeUpdate()
            }
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

    private fun insertVersion(
        projectId: UUID,
        name: String,
        deleted: Boolean,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val sql =
                if (deleted) {
                    "INSERT INTO versions (project_id, name, deleted_at) VALUES (?, ?, NOW())"
                } else {
                    "INSERT INTO versions (project_id, name) VALUES (?, ?)"
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
    fun `V010 versions 테이블 존재`() {
        assertThat(tableExists("versions")).isTrue()
    }

    @Test
    fun `V010 부분 유니크 인덱스 ux_versions_project_id_name_active 존재`() {
        assertThat(indexExists("ux_versions_project_id_name_active")).isTrue()
    }

    @Test
    fun `V010 FK 인덱스 idx_versions_project_id 존재`() {
        assertThat(indexExists("idx_versions_project_id")).isTrue()
    }

    // ── TIMESTAMPTZ / DATE 타입 검증 (DATA.md §4) ─────────────────────────────

    @Test
    fun `V010 versions created_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("versions", "created_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V010 versions updated_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("versions", "updated_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V010 versions deleted_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("versions", "deleted_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V010 versions start_date 은 DATE`() {
        assertThat(columnDataType("versions", "start_date"))
            .isEqualTo("date")
    }

    @Test
    fun `V010 versions release_date 은 DATE`() {
        assertThat(columnDataType("versions", "release_date"))
            .isEqualTo("date")
    }

    // ── 부분 유니크 동작 검증 ─────────────────────────────────────────────────

    @Test
    fun `V010 같은 프로젝트 활성 동명 버전은 유니크 위반`() {
        val projectId = insertProject()
        insertVersion(projectId, "v1.0.0", deleted = false)
        assertThatThrownBy { insertVersion(projectId, "v1.0.0", deleted = false) }
            .hasMessageContaining("ux_versions_project_id_name_active")
    }

    @Test
    fun `V010 소프트 삭제 후 동명 재생성 허용`() {
        val projectId = insertProject()
        insertVersion(projectId, "v2.0.0", deleted = true)
        // 삭제된 row 가 있어도 동명 활성 row INSERT 는 성공해야 한다 (부분 인덱스가 활성만 커버).
        insertVersion(projectId, "v2.0.0", deleted = false)
    }

    // ── V016 status / released_at 검증 (FR-VR-02) ─────────────────────────────

    @Test
    fun `V016 versions status 컬럼은 DEFAULT UNRELEASED`() {
        val projectId = insertProject()
        // status 를 명시하지 않고 INSERT — DEFAULT 'UNRELEASED' 가 채워져야 한다.
        assertThat(insertVersionAndReadStatus(projectId, "v3.0.0"))
            .isEqualTo("UNRELEASED")
    }

    @Test
    fun `V016 versions released_at 은 nullable TIMESTAMPTZ`() {
        assertThat(columnDataType("versions", "released_at"))
            .isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("versions", "released_at"))
            .isEqualTo("YES")
    }

    @Test
    fun `V016 ck_versions_status 제약은 미정의 status 값을 거부`() {
        val projectId = insertProject()
        // 'FOO' 는 ('UNRELEASED','RELEASED','ARCHIVED') 에 없으므로 CHECK 위반이어야 한다.
        assertThatThrownBy { insertVersionWithStatus(projectId, "v4.0.0", "FOO") }
            .hasMessageContaining("ck_versions_status")
    }
}
