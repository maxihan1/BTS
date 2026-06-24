// agile-planning V503 마이그레이션 검증 — sprints / sprint_issues 테이블 + 컬럼 + CHECK + UNIQUE + FK CASCADE + 인덱스 존재 확인 (FR-BL-02)

package com.bts.agileplanning.migration

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
 * Flyway V500~V503 전체 마이그레이션 체인 적용 후 sprints / sprint_issues 테이블을 검증한다.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) 의 PostgreSQL 을 직접 사용하며
 * Spring 컨텍스트 없이 실행한다. agile-planning BC 마이그레이션 체인(V500~)은 pgmq 확장을
 * 요구하지 않으므로 postgres:16-alpine 이미지로 충분하다 (BoardSchemaMigrationTest 동일 결정).
 *
 * 검증 범위 (FR-BL-02 spec §5 데이터 모델).
 * - sprints 테이블 존재 + 11개 컬럼(id/project_key/name/goal/status/start_date/end_date/version/created_at/updated_at/deleted_at)
 * - sprints.id uuid PK / project_key·name·status·version·created_at·updated_at NOT NULL
 * - sprints.status VARCHAR NOT NULL DEFAULT 'PLANNED' + CHECK(IN ('PLANNED','ACTIVE','COMPLETED'))
 * - sprints.version BIGINT NOT NULL DEFAULT 0 (낙관적 락 OCC)
 * - sprints.deleted_at timestamptz NULL 허용(소프트 삭제)
 * - 부분 인덱스 idx_sprints_project — sprints(project_key) WHERE deleted_at IS NULL
 * - sprint_issues 테이블 존재 + 3개 컬럼(sprint_id/issue_key/created_at)
 * - sprint_issues.issue_key NOT NULL / PK(sprint_id, issue_key) / UNIQUE(issue_key)
 * - sprint_issues.sprint_id FK 가 sprints(id) 참조 + ON DELETE CASCADE
 * - 인덱스 idx_sprint_issues_sprint 존재
 * - UNIQUE(issue_key) — 한 이슈는 한 스프린트에만 (중복 issue_key INSERT 거부)
 * - project_key 는 FK 없음 (issue-tracking projects 테이블 BC 격리, board 선례)
 *
 * 정보 스키마(information_schema / pg_indexes / pg_constraint) 조회로 단언한다.
 * SQL 문자열 결합 없이 prepared statement 사용.
 *
 * 참조. FR-BL-02 plan Task 1 / spec §5 데이터 모델 /
 * DATA.md §4 TIMESTAMPTZ·§4.1 V번호 범위·§7 FK 인덱스.
 */
@Testcontainers
class SprintSchemaMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_agileplanning_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/agile-planning")
                .load()
                .migrate()
        }

        // sprints 가 V503 에서 보유해야 하는 11개 컬럼.
        private val SPRINTS_COLUMNS =
            listOf(
                "id",
                "project_key",
                "name",
                "goal",
                "status",
                "start_date",
                "end_date",
                "version",
                "created_at",
                "updated_at",
                "deleted_at",
            )

        // sprint_issues 가 V503 에서 보유해야 하는 3개 컬럼.
        private val SPRINT_ISSUES_COLUMNS =
            listOf(
                "sprint_id",
                "issue_key",
                "created_at",
            )
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

    // 부분 인덱스의 술어(WHERE 절)를 pg_indexes.indexdef 에서 조회 — WHERE deleted_at IS NULL 검증용.
    @Suppress("NestedBlockDepth")
    private fun indexDef(indexName: String): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    // 주어진 테이블의 FK 가 (로컬 컬럼 → 참조 테이블) 형태로 존재하는지 확인.
    @Suppress("NestedBlockDepth")
    private fun foreignKeyExists(
        tableName: String,
        column: String,
        referencedTable: String,
    ): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*)
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage ccu
                  ON tc.constraint_name = ccu.constraint_name
                 AND tc.table_schema = ccu.table_schema
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'FOREIGN KEY'
                  AND kcu.column_name = ?
                  AND ccu.table_name = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, column)
                stmt.setString(3, referencedTable)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // 주어진 테이블의 FK delete rule(예: CASCADE)이 기대값과 일치하는지 확인.
    @Suppress("NestedBlockDepth")
    private fun foreignKeyDeleteRule(
        tableName: String,
        column: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT rc.delete_rule
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                JOIN information_schema.referential_constraints rc
                  ON tc.constraint_name = rc.constraint_name
                 AND tc.table_schema = rc.constraint_schema
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'FOREIGN KEY'
                  AND kcu.column_name = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, column)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    // 주어진 테이블의 모든 CHECK 제약 정의(pg_get_constraintdef)를 합쳐 반환 — status 허용값 술어 검증용.
    // pg_constraint.contype = 'c' 가 CHECK 제약. NOT NULL 은 별도(contype='c' 아님)이므로 영향 없음.
    @Suppress("NestedBlockDepth")
    private fun checkConstraintDefs(tableName: String): List<String> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                JOIN pg_class t ON c.conrelid = t.oid
                JOIN pg_namespace n ON t.relnamespace = n.oid
                WHERE n.nspname = 'public'
                  AND t.relname = ?
                  AND c.contype = 'c'
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    val defs = mutableListOf<String>()
                    while (rs.next()) defs.add(rs.getString(1))
                    defs
                }
            }
        }

    // sprints 한 행 INSERT — 자식 sprint_issues FK/UNIQUE 검증의 부모 행 준비용. 생성된 sprint id 반환.
    @Suppress("NestedBlockDepth")
    private fun insertSprint(projectKey: String): UUID =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO sprints (id, project_key, name) VALUES (gen_random_uuid(), ?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setString(1, projectKey)
                stmt.setString(2, "스프린트 1")
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1, UUID::class.java)
                }
            }
        }

    // sprint_issues 한 행 INSERT — UNIQUE(issue_key) / FK / PK 위반 유도용.
    private fun insertSprintIssue(
        sprintId: UUID,
        issueKey: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO sprint_issues (sprint_id, issue_key) VALUES (?, ?)",
            ).use { stmt ->
                stmt.setObject(1, sprintId)
                stmt.setString(2, issueKey)
                stmt.executeUpdate()
            }
        }
    }

    // sprints 한 행 INSERT — status 명시 지정용(CHECK 위반 유도). 위반 시 예외 전파.
    private fun insertSprintWithStatus(
        projectKey: String,
        status: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO sprints (id, project_key, name, status) VALUES (gen_random_uuid(), ?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, projectKey)
                stmt.setString(2, "스프린트")
                stmt.setString(3, status)
                stmt.executeUpdate()
            }
        }
    }

    // ── sprints 테이블/컬럼/인덱스 검증 ────────────────────────────────────────

    @Test
    fun `V503 sprints 테이블 존재`() {
        assertThat(tableExists("sprints")).isTrue()
    }

    @Test
    fun `V503 sprints 11개 컬럼 존재`() {
        assertThat(columnsOf("sprints"))
            .containsExactlyInAnyOrderElementsOf(SPRINTS_COLUMNS)
    }

    @Test
    fun `V503 sprints id 는 uuid PK NOT NULL`() {
        assertThat(columnDataType("sprints", "id")).isEqualTo("uuid")
        assertThat(columnIsNullable("sprints", "id")).isEqualTo("NO")
    }

    @Test
    fun `V503 sprints project_key 는 NOT NULL`() {
        assertThat(columnIsNullable("sprints", "project_key")).isEqualTo("NO")
    }

    @Test
    fun `V503 sprints name 은 NOT NULL`() {
        assertThat(columnIsNullable("sprints", "name")).isEqualTo("NO")
    }

    @Test
    fun `V503 sprints goal 은 NULL 허용`() {
        assertThat(columnIsNullable("sprints", "goal")).isEqualTo("YES")
    }

    @Test
    fun `V503 sprints status 는 varchar NOT NULL`() {
        assertThat(columnDataType("sprints", "status")).isEqualTo("character varying")
        assertThat(columnIsNullable("sprints", "status")).isEqualTo("NO")
    }

    @Test
    fun `V503 sprints start_date end_date 는 date NULL 허용`() {
        assertThat(columnDataType("sprints", "start_date")).isEqualTo("date")
        assertThat(columnIsNullable("sprints", "start_date")).isEqualTo("YES")
        assertThat(columnDataType("sprints", "end_date")).isEqualTo("date")
        assertThat(columnIsNullable("sprints", "end_date")).isEqualTo("YES")
    }

    @Test
    fun `V503 sprints version 은 bigint NOT NULL`() {
        assertThat(columnDataType("sprints", "version")).isEqualTo("bigint")
        assertThat(columnIsNullable("sprints", "version")).isEqualTo("NO")
    }

    @Test
    fun `V503 sprints version DEFAULT 는 0`() {
        // version 미지정 INSERT 시 0 으로 채워져야 한다(OCC 초기값).
        val sprintId = insertSprint("VER")
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT version FROM sprints WHERE id = ?").use { stmt ->
                stmt.setObject(1, sprintId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    assertThat(rs.getLong(1)).isZero()
                }
            }
        }
    }

    @Test
    fun `V503 sprints created_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("sprints", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("sprints", "created_at")).isEqualTo("NO")
    }

    @Test
    fun `V503 sprints updated_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("sprints", "updated_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("sprints", "updated_at")).isEqualTo("NO")
    }

    @Test
    fun `V503 sprints deleted_at 은 timestamptz NULL 허용 (소프트 삭제)`() {
        assertThat(columnDataType("sprints", "deleted_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("sprints", "deleted_at")).isEqualTo("YES")
    }

    @Test
    fun `V503 부분 인덱스 idx_sprints_project 존재`() {
        assertThat(indexExists("idx_sprints_project")).isTrue()
    }

    @Test
    fun `V503 idx_sprints_project 는 WHERE deleted_at IS NULL 술어를 가진다`() {
        val def = indexDef("idx_sprints_project")
        // pg_indexes.indexdef 는 술어를 "WHERE (deleted_at IS NULL)" 형태로 정규화한다.
        assertThat(def).isNotNull()
        assertThat(def!!.lowercase()).contains("where").contains("deleted_at is null")
    }

    @Test
    fun `V503 sprints project_key FK 없음 (BC 격리)`() {
        // project_key 는 issue-tracking projects 테이블을 FK 로 참조하지 않는다 (board 선례).
        assertThat(foreignKeyExists("sprints", "project_key", "projects")).isFalse()
    }

    // ── sprints.status CHECK 검증 ──────────────────────────────────────────────

    @Test
    fun `V503 sprints status DEFAULT 는 PLANNED`() {
        // status 미지정 INSERT 시 'PLANNED' 로 채워져야 한다.
        val sprintId = insertSprint("DEF-STATUS")
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT status FROM sprints WHERE id = ?").use { stmt ->
                stmt.setObject(1, sprintId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    assertThat(rs.getString(1)).isEqualTo("PLANNED")
                }
            }
        }
    }

    @Test
    fun `V503 sprints status 허용값 CHECK 제약 존재`() {
        // CHECK (status IN ('PLANNED','ACTIVE','COMPLETED')) — pg_get_constraintdef 표현으로 검증.
        val defs = checkConstraintDefs("sprints").map { it.lowercase() }
        assertThat(defs).anySatisfy { def ->
            assertThat(def)
                .contains("status")
                .contains("planned")
                .contains("active")
                .contains("completed")
        }
    }

    @Test
    fun `V503 sprints status 허용값들은 INSERT 가능`() {
        // PLANNED / ACTIVE / COMPLETED 셋 다 CHECK 를 통과해야 한다(예외 없음).
        insertSprintWithStatus("ST-PLANNED", "PLANNED")
        insertSprintWithStatus("ST-ACTIVE", "ACTIVE")
        insertSprintWithStatus("ST-COMPLETED", "COMPLETED")
    }

    @Test
    fun `V503 sprints status 허용 외 값은 CHECK 위반`() {
        // 허용 목록 밖 임의 값은 CHECK 위반이어야 한다.
        assertThatThrownBy { insertSprintWithStatus("ST-BAD", "INVALID_VALUE") }
            .hasMessageContaining("sprints_status_allowed")
    }

    // ── sprint_issues 테이블/컬럼/FK/제약 검증 ─────────────────────────────────

    @Test
    fun `V503 sprint_issues 테이블 존재`() {
        assertThat(tableExists("sprint_issues")).isTrue()
    }

    @Test
    fun `V503 sprint_issues 3개 컬럼 존재`() {
        assertThat(columnsOf("sprint_issues"))
            .containsExactlyInAnyOrderElementsOf(SPRINT_ISSUES_COLUMNS)
    }

    @Test
    fun `V503 sprint_issues sprint_id 는 uuid NOT NULL`() {
        assertThat(columnDataType("sprint_issues", "sprint_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("sprint_issues", "sprint_id")).isEqualTo("NO")
    }

    @Test
    fun `V503 sprint_issues issue_key 는 NOT NULL`() {
        assertThat(columnIsNullable("sprint_issues", "issue_key")).isEqualTo("NO")
    }

    @Test
    fun `V503 sprint_issues created_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("sprint_issues", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("sprint_issues", "created_at")).isEqualTo("NO")
    }

    @Test
    fun `V503 sprint_issues sprint_id FK 는 sprints 를 참조`() {
        assertThat(foreignKeyExists("sprint_issues", "sprint_id", "sprints")).isTrue()
    }

    @Test
    fun `V503 sprint_issues sprint_id FK 는 ON DELETE CASCADE`() {
        assertThat(foreignKeyDeleteRule("sprint_issues", "sprint_id")).isEqualTo("CASCADE")
    }

    @Test
    fun `V503 인덱스 idx_sprint_issues_sprint 존재`() {
        assertThat(indexExists("idx_sprint_issues_sprint")).isTrue()
    }

    // ── UNIQUE(issue_key) / PK / CASCADE 동작 검증 ─────────────────────────────

    @Test
    fun `V503 같은 issue_key 를 다른 스프린트에 INSERT 는 유니크 위반`() {
        // 한 이슈는 한 스프린트에만 속한다 — issue_key 전역 UNIQUE.
        val sprintA = insertSprint("UNIQ-A")
        val sprintB = insertSprint("UNIQ-B")
        insertSprintIssue(sprintA, "PRJ-1")
        // 같은 issue_key 를 다른 스프린트에 넣으면 UNIQUE(issue_key) 위반이어야 한다.
        assertThatThrownBy { insertSprintIssue(sprintB, "PRJ-1") }
            .hasMessageContaining("issue_key")
    }

    @Test
    fun `V503 같은 sprint_id+issue_key 중복 INSERT 는 PK 위반`() {
        val sprintId = insertSprint("PK-DUP")
        insertSprintIssue(sprintId, "PRJ-10")
        // 같은 (sprint_id, issue_key) 조합 재INSERT 는 PK 위반이어야 한다.
        assertThatThrownBy { insertSprintIssue(sprintId, "PRJ-10") }
            .hasMessageContaining("sprint_issues")
    }

    @Test
    fun `V503 sprint_id CASCADE — 부모 sprints 삭제 시 sprint_issues 도 삭제`() {
        val sprintId = insertSprint("CASCADE")
        insertSprintIssue(sprintId, "PRJ-20")
        // 부모 sprints 행 하드 삭제 → CASCADE 로 sprint_issues 자식 행도 사라져야 한다.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("DELETE FROM sprints WHERE id = ?").use { stmt ->
                stmt.setObject(1, sprintId)
                stmt.executeUpdate()
            }
            conn.prepareStatement("SELECT COUNT(*) FROM sprint_issues WHERE sprint_id = ?").use { stmt ->
                stmt.setObject(1, sprintId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    assertThat(rs.getInt(1)).isZero()
                }
            }
        }
    }
}
