// V600 마이그레이션 검증 — saved_filters 테이블 + 컬럼 + timestamptz + UNIQUE(owner_id,name) + 인덱스 존재 확인 (FR-SR-03)

package com.bts.search.savedfilter.migration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * Flyway V600~ 마이그레이션 적용 후 saved_filters 테이블을 검증한다.
 *
 * search-export-import BC 의 첫 영속성 마이그레이션(V600) 검증이다. 이 BC 는 이전까지
 * AQL 파서 + 컨트롤러만 가진 무상태 모듈이었고, SavedFilter 가 첫 영속 엔티티다.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) 의 PostgreSQL 을
 * **JVM 단위 singleton** 으로 기동한다(companion object `.apply { start() }`).
 * `@Container` 라이프사이클 대신 JVM 종료 시 Ryuk 자동 정리에 위임해 동시 suite flaky 를 회피한다
 * (메모리 concurrent-testcontainers-suite-flaky). Spring 컨텍스트 없이 raw JDBC + information_schema
 * 조회로 단언한다. 마이그레이션 체인에 FR-EX-02 V602(pgmq 확장 + pgmq.create) 가 포함되므로
 * postgres:16-alpine 이 아닌 quay.io/tembo/pg16-pgmq:latest 를 사용한다 (ADR 2026-05-22-pgmq-postgres-image).
 *
 * 검증 범위 (FR-SR-03 plan Task 1 / spec §데이터 모델 / DATA.md §4 TIMESTAMPTZ 강제).
 * - saved_filters 테이블 존재 + 8개 컬럼(id/owner_id/name/aql_query/project_key/created_at/updated_at/version)
 * - id/owner_id = uuid NOT NULL, name/project_key = varchar NOT NULL
 * - aql_query = text NOT NULL, version = bigint NOT NULL
 * - created_at/updated_at = timestamptz NOT NULL (DATA.md §4.1#4 타임존 강제 — TIMESTAMP without tz 금지)
 * - UNIQUE(owner_id, name) 제약 존재 + 같은 (owner_id, name) 중복 INSERT 위반
 * - 인덱스 idx_saved_filters_owner / idx_saved_filters_project 존재
 * - owner_id 는 FK 없음 (identity-access users.id UUID 를 값으로만 보관, BC 격리)
 *
 * 정보 스키마(information_schema / pg_indexes / pg_constraint) 조회로 단언한다.
 * SQL 문자열 결합 없이 prepared statement 를 사용한다.
 */
class SavedFiltersMigrationTest {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container.
         * `.apply { start() }` 로 JVM 시작 시점에 한 번만 기동되며, Ryuk 이 JVM 종료 시 자동 정리한다.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_search_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        // saved_filters 가 보유해야 하는 8개 컬럼.
        private val SAVED_FILTERS_COLUMNS =
            listOf(
                "id",
                "owner_id",
                "name",
                "aql_query",
                "project_key",
                "created_at",
                "updated_at",
                "version",
            )

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/search-export-import")
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

    // 주어진 테이블의 모든 컬럼명을 반환.
    @Suppress("NestedBlockDepth")
    private fun columnsOf(tableName: String): List<String> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT column_name FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    val cols = mutableListOf<String>()
                    while (rs.next()) cols.add(rs.getString(1))
                    cols
                }
            }
        }

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

    // 주어진 이름의 제약(constraint)이 존재하는지 확인 — UNIQUE(owner_id, name) 검증용.
    private fun constraintExists(constraintName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = ?",
            ).use { stmt ->
                stmt.setString(1, constraintName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // 주어진 테이블의 특정 컬럼이 외래 키(FK)를 갖는지 확인 — BC 격리(owner_id FK 부재) 검증용.
    @Suppress("NestedBlockDepth")
    private fun foreignKeyExists(
        tableName: String,
        column: String,
    ): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*)
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'FOREIGN KEY'
                  AND kcu.column_name = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, column)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // saved_filters 한 행 INSERT — UNIQUE(owner_id, name) 위반 유도용. id 는 DEFAULT 가 없어 명시 지정.
    private fun insertSavedFilter(
        ownerId: UUID,
        name: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO saved_filters (id, owner_id, name, aql_query, project_key)" +
                    " VALUES (?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setObject(2, ownerId)
                stmt.setString(3, name)
                stmt.setString(4, "status = OPEN")
                stmt.setString(5, "ATLAS")
                stmt.executeUpdate()
            }
        }
    }

    // ── 테이블 / 컬럼 존재 검증 ────────────────────────────────────────────────

    @Test
    fun `V600 saved_filters 테이블 존재`() {
        assertThat(tableExists("saved_filters")).isTrue()
    }

    @Test
    fun `V600 saved_filters 8개 컬럼 존재`() {
        assertThat(columnsOf("saved_filters"))
            .containsExactlyInAnyOrderElementsOf(SAVED_FILTERS_COLUMNS)
    }

    @Test
    fun `V600 saved_filters id 는 uuid PK NOT NULL`() {
        assertThat(columnDataType("saved_filters", "id")).isEqualTo("uuid")
        assertThat(columnIsNullable("saved_filters", "id")).isEqualTo("NO")
    }

    @Test
    fun `V600 saved_filters owner_id 는 uuid NOT NULL`() {
        assertThat(columnDataType("saved_filters", "owner_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("saved_filters", "owner_id")).isEqualTo("NO")
    }

    @Test
    fun `V600 saved_filters name 은 varchar NOT NULL`() {
        assertThat(columnDataType("saved_filters", "name")).isEqualTo("character varying")
        assertThat(columnIsNullable("saved_filters", "name")).isEqualTo("NO")
    }

    @Test
    fun `V600 saved_filters aql_query 는 text NOT NULL`() {
        assertThat(columnDataType("saved_filters", "aql_query")).isEqualTo("text")
        assertThat(columnIsNullable("saved_filters", "aql_query")).isEqualTo("NO")
    }

    @Test
    fun `V600 saved_filters project_key 는 varchar NOT NULL`() {
        assertThat(columnDataType("saved_filters", "project_key")).isEqualTo("character varying")
        assertThat(columnIsNullable("saved_filters", "project_key")).isEqualTo("NO")
    }

    @Test
    fun `V600 saved_filters version 은 bigint NOT NULL`() {
        assertThat(columnDataType("saved_filters", "version")).isEqualTo("bigint")
        assertThat(columnIsNullable("saved_filters", "version")).isEqualTo("NO")
    }

    // ── timestamptz 강제 검증 (DATA.md §4 — TIMESTAMP without tz 금지) ──────────

    @Test
    fun `V600 saved_filters created_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("saved_filters", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("saved_filters", "created_at")).isEqualTo("NO")
    }

    @Test
    fun `V600 saved_filters updated_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("saved_filters", "updated_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("saved_filters", "updated_at")).isEqualTo("NO")
    }

    // ── UNIQUE(owner_id, name) 검증 ────────────────────────────────────────────

    @Test
    fun `V600 uq_saved_filters_owner_name 유니크 제약 존재`() {
        assertThat(constraintExists("uq_saved_filters_owner_name")).isTrue()
    }

    @Test
    fun `V600 같은 owner_id+name 조합 중복 INSERT 는 유니크 위반`() {
        val ownerId = UUID.randomUUID()
        insertSavedFilter(ownerId, "내 급한 일")
        // 같은 (owner_id, name) 조합은 중복이므로 UNIQUE 위반이어야 한다.
        assertThatThrownBy { insertSavedFilter(ownerId, "내 급한 일") }
            .hasMessageContaining("uq_saved_filters_owner_name")
    }

    @Test
    fun `V600 같은 name 이라도 owner_id 가 다르면 INSERT 허용`() {
        // owner 가 다르면 같은 이름의 필터를 각자 가질 수 있어야 한다(유니크 범위 = owner 내).
        insertSavedFilter(UUID.randomUUID(), "공통 이름")
        insertSavedFilter(UUID.randomUUID(), "공통 이름")
    }

    // ── 인덱스 검증 ────────────────────────────────────────────────────────────

    @Test
    fun `V600 idx_saved_filters_owner 인덱스 존재`() {
        assertThat(indexExists("idx_saved_filters_owner")).isTrue()
    }

    @Test
    fun `V600 idx_saved_filters_project 인덱스 존재`() {
        assertThat(indexExists("idx_saved_filters_project")).isTrue()
    }

    // ── BC 격리 검증 ───────────────────────────────────────────────────────────

    @Test
    fun `V600 saved_filters owner_id 는 FK 없음 (BC 격리)`() {
        // owner_id 는 identity-access users.id 를 값으로만 보관하며 FK 로 참조하지 않는다(notification 선례).
        assertThat(foreignKeyExists("saved_filters", "owner_id")).isFalse()
    }
}
