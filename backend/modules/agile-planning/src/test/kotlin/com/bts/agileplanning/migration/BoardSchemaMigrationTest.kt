// agile-planning V500 마이그레이션 검증 — boards / board_columns 테이블 + 컬럼 + UNIQUE + FK CASCADE + 부분 인덱스 존재 확인 (FR-BD-01)

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
 * Flyway V500~ 전체 마이그레이션 체인 적용 후 boards / board_columns 테이블을 검증한다.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) 의 PostgreSQL 을 직접 사용하며
 * Spring 컨텍스트 없이 실행한다. agile-planning BC 마이그레이션 체인(V500~)은 pgmq 확장을
 * 요구하지 않으므로 postgres:16-alpine 이미지로 충분하다 (UserNotificationSubsSchemaTest 동일 결정).
 *
 * 검증 범위 (FR-BD-01 §데이터 모델 + FR-BD-03 V501 WIP/스윔레인).
 * - boards 테이블 존재 + 7개 컬럼(id/project_key/name/created_at/updated_at/deleted_at/swimlane_field)
 * - boards.deleted_at NULL 허용(소프트 삭제) / project_key·name·created_at·updated_at NOT NULL
 * - 부분 인덱스 idx_boards_project_key — boards(project_key) WHERE deleted_at IS NULL
 * - board_columns 테이블 존재 + 7개 컬럼(id/board_id/state_key/name/category/display_order/wip_limit)
 * - board_columns.board_id FK 가 boards(id) 참조 + ON DELETE CASCADE
 * - UNIQUE(board_id, state_key) 제약 — 같은 조합 중복 INSERT 시 위반
 * - project_key 는 FK 없음 (issue-tracking projects 테이블 BC 격리, notification 선례)
 * - (V501) board_columns.wip_limit INTEGER NULL + CHECK(wip_limit IS NULL OR wip_limit > 0)
 * - (V501) boards.swimlane_field VARCHAR NOT NULL DEFAULT 'NONE' + CHECK(IN ('NONE','ASSIGNEE','PRIORITY'))
 *
 * 정보 스키마(information_schema / pg_indexes / pg_constraint) 조회로 단언한다.
 * SQL 문자열 결합 없이 prepared statement 사용.
 *
 * 참조. FR-BD-01 plan Task 6 · FR-BD-03 plan Task 1 / spec §데이터 모델 /
 * DATA.md §4 TIMESTAMPTZ·§4.1 V번호 범위·§7 FK 인덱스.
 */
@Testcontainers
class BoardSchemaMigrationTest {
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

        // board_columns 가 보유해야 하는 7개 컬럼 (V501 에서 wip_limit 추가).
        private val BOARD_COLUMNS_COLUMNS =
            listOf(
                "id",
                "board_id",
                "state_key",
                "name",
                "category",
                "display_order",
                "wip_limit",
            )

        // boards 가 V501 이후 보유해야 하는 7개 컬럼 (swimlane_field 추가).
        private val BOARDS_COLUMNS_V501 =
            listOf(
                "id",
                "project_key",
                "name",
                "created_at",
                "updated_at",
                "deleted_at",
                "swimlane_field",
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

    // 주어진 테이블의 모든 CHECK 제약 정의(pg_get_constraintdef)를 합쳐 반환 — wip_limit / swimlane_field 술어 검증용.
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

    // board_columns 한 행 INSERT — wip_limit 명시 지정용(CHECK 위반 유도). 위반 시 예외 전파.
    private fun insertBoardColumnWithWipLimit(
        boardId: UUID,
        stateKey: String,
        wipLimit: Int,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO board_columns (board_id, state_key, name, category, display_order, wip_limit)" +
                    " VALUES (?, ?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, boardId)
                stmt.setString(2, stateKey)
                stmt.setString(3, "할 일")
                stmt.setString(4, "TODO")
                stmt.setInt(5, 0)
                stmt.setInt(6, wipLimit)
                stmt.executeUpdate()
            }
        }
    }

    // boards 한 행 INSERT — swimlane_field 명시 지정용(CHECK 위반 유도). 위반 시 예외 전파.
    private fun insertBoardWithSwimlaneField(
        projectKey: String,
        swimlaneField: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO boards (project_key, name, swimlane_field) VALUES (?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, projectKey)
                stmt.setString(2, "보드")
                stmt.setString(3, swimlaneField)
                stmt.executeUpdate()
            }
        }
    }

    // boards 한 행 INSERT — board_columns FK / UNIQUE 검증의 부모 행 준비용. 생성된 board id 반환.
    @Suppress("NestedBlockDepth")
    private fun insertBoard(projectKey: String): UUID =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO boards (project_key, name) VALUES (?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setString(1, projectKey)
                stmt.setString(2, "보드")
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1, UUID::class.java)
                }
            }
        }

    // board_columns 한 행 INSERT — UNIQUE(board_id, state_key) 위반 유도용.
    private fun insertBoardColumn(
        boardId: UUID,
        stateKey: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO board_columns (board_id, state_key, name, category, display_order)" +
                    " VALUES (?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, boardId)
                stmt.setString(2, stateKey)
                stmt.setString(3, "할 일")
                stmt.setString(4, "TODO")
                stmt.setInt(5, 0)
                stmt.executeUpdate()
            }
        }
    }

    // ── boards 테이블/컬럼/인덱스 검증 ─────────────────────────────────────────

    @Test
    fun `V500 boards 테이블 존재`() {
        assertThat(tableExists("boards")).isTrue()
    }

    @Test
    fun `V501 boards 7개 컬럼 존재 (swimlane_field 추가)`() {
        assertThat(columnsOf("boards"))
            .containsExactlyInAnyOrderElementsOf(BOARDS_COLUMNS_V501)
    }

    @Test
    fun `V500 boards id 는 uuid PK NOT NULL`() {
        assertThat(columnDataType("boards", "id")).isEqualTo("uuid")
        assertThat(columnIsNullable("boards", "id")).isEqualTo("NO")
    }

    @Test
    fun `V500 boards project_key 는 NOT NULL`() {
        assertThat(columnIsNullable("boards", "project_key")).isEqualTo("NO")
    }

    @Test
    fun `V500 boards name 은 NOT NULL`() {
        assertThat(columnIsNullable("boards", "name")).isEqualTo("NO")
    }

    @Test
    fun `V500 boards created_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("boards", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("boards", "created_at")).isEqualTo("NO")
    }

    @Test
    fun `V500 boards updated_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("boards", "updated_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("boards", "updated_at")).isEqualTo("NO")
    }

    @Test
    fun `V500 boards deleted_at 은 timestamptz NULL 허용 (소프트 삭제)`() {
        assertThat(columnDataType("boards", "deleted_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("boards", "deleted_at")).isEqualTo("YES")
    }

    @Test
    fun `V500 부분 인덱스 idx_boards_project_key 존재`() {
        assertThat(indexExists("idx_boards_project_key")).isTrue()
    }

    @Test
    fun `V500 idx_boards_project_key 는 WHERE deleted_at IS NULL 술어를 가진다`() {
        val def = indexDef("idx_boards_project_key")
        // pg_indexes.indexdef 는 술어를 "WHERE (deleted_at IS NULL)" 형태로 정규화한다.
        assertThat(def).isNotNull()
        assertThat(def!!.lowercase()).contains("where").contains("deleted_at is null")
    }

    // ── board_columns 테이블/컬럼/FK 검증 ──────────────────────────────────────

    @Test
    fun `V500 board_columns 테이블 존재`() {
        assertThat(tableExists("board_columns")).isTrue()
    }

    @Test
    fun `V500 board_columns 6개 컬럼 존재`() {
        assertThat(columnsOf("board_columns"))
            .containsExactlyInAnyOrderElementsOf(BOARD_COLUMNS_COLUMNS)
    }

    @Test
    fun `V500 board_columns board_id 는 NOT NULL`() {
        assertThat(columnDataType("board_columns", "board_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("board_columns", "board_id")).isEqualTo("NO")
    }

    @Test
    fun `V500 board_columns state_key 는 NOT NULL`() {
        assertThat(columnIsNullable("board_columns", "state_key")).isEqualTo("NO")
    }

    @Test
    fun `V500 board_columns category 는 NOT NULL`() {
        assertThat(columnIsNullable("board_columns", "category")).isEqualTo("NO")
    }

    @Test
    fun `V500 board_columns display_order 는 integer NOT NULL`() {
        assertThat(columnDataType("board_columns", "display_order")).isEqualTo("integer")
        assertThat(columnIsNullable("board_columns", "display_order")).isEqualTo("NO")
    }

    @Test
    fun `V500 board_columns board_id FK 는 boards 를 참조`() {
        assertThat(foreignKeyExists("board_columns", "board_id", "boards")).isTrue()
    }

    @Test
    fun `V500 board_columns board_id FK 는 ON DELETE CASCADE`() {
        assertThat(foreignKeyDeleteRule("board_columns", "board_id")).isEqualTo("CASCADE")
    }

    @Test
    fun `V500 boards project_key FK 없음 (BC 격리)`() {
        // project_key 는 issue-tracking projects 테이블을 FK 로 참조하지 않는다 (notification 선례).
        assertThat(foreignKeyExists("boards", "project_key", "projects")).isFalse()
    }

    // ── UNIQUE(board_id, state_key) 동작 검증 ──────────────────────────────────

    @Test
    fun `V500 같은 board_id+state_key 조합 중복 INSERT 는 유니크 위반`() {
        val boardId = insertBoard("BTS")
        insertBoardColumn(boardId, "open")
        // 같은 (board_id, state_key) 조합은 중복 컬럼이므로 UNIQUE 위반이어야 한다.
        assertThatThrownBy { insertBoardColumn(boardId, "open") }
            .hasMessageContaining("board_columns")
    }

    @Test
    fun `V500 board_id CASCADE — 부모 boards 삭제 시 board_columns 도 삭제`() {
        val boardId = insertBoard("CASCADE")
        insertBoardColumn(boardId, "in_progress")
        // 부모 boards 행 하드 삭제 → CASCADE 로 board_columns 자식 행도 사라져야 한다.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("DELETE FROM boards WHERE id = ?").use { stmt ->
                stmt.setObject(1, boardId)
                stmt.executeUpdate()
            }
            conn.prepareStatement("SELECT COUNT(*) FROM board_columns WHERE board_id = ?").use { stmt ->
                stmt.setObject(1, boardId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    assertThat(rs.getInt(1)).isZero()
                }
            }
        }
    }

    // ── V501 board_columns.wip_limit 검증 (FR-BD-03 WIP 제한) ──────────────────

    @Test
    fun `V501 board_columns wip_limit 은 integer NULL 허용`() {
        assertThat(columnDataType("board_columns", "wip_limit")).isEqualTo("integer")
        assertThat(columnIsNullable("board_columns", "wip_limit")).isEqualTo("YES")
    }

    @Test
    fun `V501 board_columns wip_limit 양수 CHECK 제약 존재`() {
        // CHECK (wip_limit IS NULL OR wip_limit > 0) — pg_get_constraintdef 표현으로 검증.
        val defs = checkConstraintDefs("board_columns").map { it.lowercase() }
        assertThat(defs).anySatisfy { def ->
            assertThat(def).contains("wip_limit").contains("is null").contains("> 0")
        }
    }

    @Test
    fun `V501 board_columns wip_limit 양수는 허용`() {
        val boardId = insertBoard("WIP-OK")
        // 양수 wip_limit 는 CHECK 를 통과해야 한다(예외 없음).
        insertBoardColumnWithWipLimit(boardId, "open", 5)
    }

    @Test
    fun `V501 board_columns wip_limit 0 은 CHECK 위반`() {
        val boardId = insertBoard("WIP-ZERO")
        // wip_limit = 0 은 양수가 아니므로 CHECK 위반이어야 한다.
        assertThatThrownBy { insertBoardColumnWithWipLimit(boardId, "open", 0) }
            .hasMessageContaining("board_columns_wip_limit_positive")
    }

    @Test
    fun `V501 board_columns wip_limit 음수는 CHECK 위반`() {
        val boardId = insertBoard("WIP-NEG")
        // wip_limit < 0 도 CHECK 위반이어야 한다.
        assertThatThrownBy { insertBoardColumnWithWipLimit(boardId, "open", -3) }
            .hasMessageContaining("board_columns_wip_limit_positive")
    }

    // ── V501 boards.swimlane_field 검증 (FR-BD-03 스윔레인) ────────────────────

    @Test
    fun `V501 boards swimlane_field 는 varchar NOT NULL`() {
        assertThat(columnDataType("boards", "swimlane_field")).isEqualTo("character varying")
        assertThat(columnIsNullable("boards", "swimlane_field")).isEqualTo("NO")
    }

    @Test
    fun `V501 boards swimlane_field DEFAULT 는 NONE`() {
        // DEFAULT 'NONE' 이면 swimlane_field 미지정 INSERT 시 'NONE' 으로 채워진다.
        val boardId = insertBoard("SWIM-DEFAULT")
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT swimlane_field FROM boards WHERE id = ?").use { stmt ->
                stmt.setObject(1, boardId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    assertThat(rs.getString(1)).isEqualTo("NONE")
                }
            }
        }
    }

    @Test
    fun `V501 boards swimlane_field 허용값 CHECK 제약 존재`() {
        // CHECK (swimlane_field IN ('NONE','ASSIGNEE','PRIORITY')) — pg_get_constraintdef 표현으로 검증.
        val defs = checkConstraintDefs("boards").map { it.lowercase() }
        assertThat(defs).anySatisfy { def ->
            assertThat(def)
                .contains("swimlane_field")
                .contains("none")
                .contains("assignee")
                .contains("priority")
        }
    }

    @Test
    fun `V501 boards swimlane_field 허용값들은 INSERT 가능`() {
        // NONE / ASSIGNEE / PRIORITY 셋 다 CHECK 를 통과해야 한다(예외 없음).
        insertBoardWithSwimlaneField("SWIM-NONE", "NONE")
        insertBoardWithSwimlaneField("SWIM-ASSIGNEE", "ASSIGNEE")
        insertBoardWithSwimlaneField("SWIM-PRIORITY", "PRIORITY")
    }

    @Test
    fun `V501 boards swimlane_field 허용 외 값은 CHECK 위반`() {
        // 허용 목록 밖 임의 값은 CHECK 위반이어야 한다.
        assertThatThrownBy { insertBoardWithSwimlaneField("SWIM-BAD", "INVALID_VALUE") }
            .hasMessageContaining("boards_swimlane_field_allowed")
    }

    // ── V502 boards.swimlane_field EPIC 활성화 검증 (FR-EP-01 완료로 이연 해제) ──

    @Test
    fun `V502 boards swimlane_field CHECK 제약이 EPIC 을 포함한다`() {
        // V502 마이그레이션으로 CHECK 가 NONE/ASSIGNEE/PRIORITY/EPIC 4종을 허용해야 한다.
        val defs = checkConstraintDefs("boards").map { it.lowercase() }
        assertThat(defs).anySatisfy { def ->
            assertThat(def)
                .contains("swimlane_field")
                .contains("none")
                .contains("assignee")
                .contains("priority")
                .contains("epic")
        }
    }

    @Test
    fun `V502 boards swimlane_field EPIC INSERT 가 허용된다`() {
        // V502 이후 EPIC 은 유효한 스윔레인 값이므로 CHECK 를 통과해야 한다.
        insertBoardWithSwimlaneField("SWIM-EPIC", "EPIC")
    }
}
