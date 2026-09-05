// V509 검증 — board_card_layout_fields 는 뷰(BOARD/BACKLOG)마다 따로고, 상한 3을 DB CHECK 가 지킨다

package com.bts.agileplanning.migration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * CHECK 제약 위반 SQLSTATE.
 *
 * ★ 「아무 SQLException」으로 재면 **테이블이 아예 없을 때(42P01)도 통과한다** — 부정 단언이
 * 공허해진다. red 단계에서 실측으로 확인한 함정이라 SQLSTATE 까지 못 박는다.
 */
private const val CHECK_VIOLATION = "23514"

/**
 * `V509__board_settings_tabs.sql` 의 `board_card_layout_fields` 를 검증한다 (갭 B · J17·J18).
 *
 * ## 판정은 **한 쌍**이다 — 한쪽만 두면 잘못된 스키마가 통과한다
 *
 * | 축 | 무엇을 재는가 | 이 축이 없으면 통과해 버리는 잘못된 스키마 |
 * |---|---|---|
 * | ① 뷰별 분리 | `BOARD` 와 `BACKLOG` 에 **각각** 세 칸이 들어간다 | 뷰 구분 없이 보드 전체에 세 칸인 스키마 |
 * | ② 상한 3 | 네 번째(`position = 3`)가 제약 위반으로 죽는다 | 상한이 아예 없는 스키마 |
 *
 * ★ ① 은 두 뷰에 **일부러 다른 field_key** 를 넣고 되읽는다. 같은 값을 넣고 재면 두 뷰가 구성을
 * 공유하는 구현도 통과한다(스펙 §완료 기준 3).
 *
 * ★ ② 를 DB `CHECK` 로 재는 이유. 서비스 사전 검사만 두면 두 관리자가 동시에 각자 3개를
 * 통과시켜 6개가 들어간다 — `#444` 가 `X1` 경합에서 이미 이름 붙인 양식이다.
 *
 * ## 왜 전용 컨테이너인가
 * `AgilePlanningTestcontainersConfig` 의 공유 컨테이너는 무조건 최신까지 밀어버린다. 스키마
 * 판정은 체인 전체를 태운 결과만 보면 되므로 전용 컨테이너에 `target` 없이 한 번에 올린다
 * (`V510` 이 들어와도 그대로 돈다 — [BoardSettingsMigrationTest] 와 같은 이유로 숫자를 박지 않는다).
 *
 * 참조. `docs/specs/2026-09-05-board-settings-remaining-tabs-177.md` §데이터 모델 · 완료 기준 3 ·
 * `docs/plans/2026-09-05-board-settings-remaining-tabs-177.md` Task 2 · DATA.md §4·§4.1·§7.
 */
@Testcontainers
class BoardCardLayoutSchemaTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_agileplanning_card_layout_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @BeforeAll
        @JvmStatic
        fun migrate() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/agile-planning")
                .load()
                .migrate()
        }

        fun conn(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

        private fun seedBoard(
            projectKey: String,
            name: String,
        ): UUID =
            conn().use { c ->
                val id = UUID.randomUUID()
                c.prepareStatement("INSERT INTO boards (id, project_key, name) VALUES (?, ?, ?)").use { ps ->
                    ps.setObject(1, id)
                    ps.setString(2, projectKey)
                    ps.setString(3, name)
                    ps.executeUpdate()
                }
                id
            }
    }

    // ── 헬퍼 — 전부 prepared statement (SQL 문자열 결합 금지) ────────────────────

    /** `format_type` 으로 선언 타입을 원문 그대로 읽는다 — 길이까지 본다. */
    @Suppress("NestedBlockDepth")
    private fun declaredType(
        table: String,
        column: String,
    ): String? =
        conn().use { c ->
            c.prepareStatement(
                """
                SELECT format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                JOIN pg_class t ON a.attrelid = t.oid
                JOIN pg_namespace n ON t.relnamespace = n.oid
                WHERE n.nspname = 'public' AND t.relname = ? AND a.attname = ?
                  AND a.attnum > 0 AND NOT a.attisdropped
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, table)
                ps.setString(2, column)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun columnsOf(table: String): List<String> =
        queryStrings(
            "SELECT column_name FROM information_schema.columns" +
                " WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position",
            table,
        )

    private fun primaryKeyColumns(table: String): List<String> =
        queryStrings(
            """
            SELECT kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
             AND tc.table_schema = kcu.table_schema
            WHERE tc.table_schema = 'public' AND tc.table_name = ?
              AND tc.constraint_type = 'PRIMARY KEY'
            ORDER BY kcu.ordinal_position
            """.trimIndent(),
            table,
        )

    /** FK 의 (참조 테이블, delete rule) 쌍. 없으면 null. */
    @Suppress("NestedBlockDepth")
    private fun foreignKeyTarget(
        table: String,
        column: String,
    ): Pair<String, String>? =
        conn().use { c ->
            c.prepareStatement(
                """
                SELECT ccu.table_name, rc.delete_rule
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage ccu
                  ON tc.constraint_name = ccu.constraint_name
                 AND tc.table_schema = ccu.table_schema
                JOIN information_schema.referential_constraints rc
                  ON tc.constraint_name = rc.constraint_name
                 AND tc.table_schema = rc.constraint_schema
                WHERE tc.table_schema = 'public' AND tc.table_name = ?
                  AND tc.constraint_type = 'FOREIGN KEY' AND kcu.column_name = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, table)
                ps.setString(2, column)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
            }
        }

    private fun indexDefs(table: String): List<String> =
        queryStrings("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?", table)

    @Suppress("NestedBlockDepth")
    private fun queryStrings(
        sql: String,
        arg: String,
    ): List<String> =
        conn().use { c ->
            c.prepareStatement(sql).use { ps ->
                ps.setString(1, arg)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<String>()
                    while (rs.next()) out.add(rs.getString(1))
                    out
                }
            }
        }

    private fun insertLayoutField(
        boardId: UUID,
        viewScope: String,
        position: Int,
        fieldKey: String,
    ) {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO board_card_layout_fields (board_id, view_scope, position, field_key)" +
                    " VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, boardId)
                ps.setString(2, viewScope)
                ps.setShort(3, position.toShort())
                ps.setString(4, fieldKey)
                ps.executeUpdate()
            }
        }
    }

    /** 한 보드·한 뷰의 field_key 를 position 순으로 읽는다. */
    @Suppress("NestedBlockDepth")
    private fun fieldKeysOf(
        boardId: UUID,
        viewScope: String,
    ): List<String> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT field_key FROM board_card_layout_fields" +
                    " WHERE board_id = ? AND view_scope = ? ORDER BY position",
            ).use { ps ->
                ps.setObject(1, boardId)
                ps.setString(2, viewScope)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<String>()
                    while (rs.next()) out.add(rs.getString(1))
                    out
                }
            }
        }

    /** [block] 이 던진 SQLSTATE. 죽지 **않으면** null 이라 「통과해 버렸다」가 그대로 드러난다. */
    private fun sqlStateOf(block: () -> Unit): String? =
        try {
            block()
            null
        } catch (e: SQLException) {
            e.sqlState
        }

    // ── 구조 (J17·J18 · DATA.md §7) ─────────────────────────────────────────────

    @Test
    fun `board_card_layout_fields 가 네 칸으로 생긴다`() {
        assertThat(columnsOf("board_card_layout_fields"))
            .containsExactlyInAnyOrder("board_id", "view_scope", "position", "field_key")
        assertThat(declaredType("board_card_layout_fields", "board_id")).isEqualTo("uuid")
        assertThat(declaredType("board_card_layout_fields", "view_scope")).isEqualTo("character varying(16)")
        assertThat(declaredType("board_card_layout_fields", "position")).isEqualTo("smallint")
        assertThat(declaredType("board_card_layout_fields", "field_key")).isEqualTo("character varying(128)")
    }

    @Test
    fun `기본키가 board_id view_scope position 복합이다`() {
        assertThat(primaryKeyColumns("board_card_layout_fields"))
            .containsExactly("board_id", "view_scope", "position")
    }

    // ── ① 뷰별 분리 (J18 · 완료 기준 3) ─────────────────────────────────────────

    @Test
    fun `BOARD 와 BACKLOG 에 각각 세 칸이 들어가고 구성이 서로 다르다`() {
        val boardId = seedBoard("CARDSPLIT", "뷰별 분리 판정용 보드")
        val boardKeys = listOf("assignee", "priority", "labels")
        val backlogKeys = listOf("epic", "story_points", "due_date")

        boardKeys.forEachIndexed { i, key -> insertLayoutField(boardId, "BOARD", i, key) }
        backlogKeys.forEachIndexed { i, key -> insertLayoutField(boardId, "BACKLOG", i, key) }

        // 일부러 다른 값을 넣는다 — 같은 값이면 두 뷰가 구성을 공유하는 구현도 통과한다.
        assertThat(fieldKeysOf(boardId, "BOARD")).containsExactlyElementsOf(boardKeys)
        assertThat(fieldKeysOf(boardId, "BACKLOG")).containsExactlyElementsOf(backlogKeys)
    }

    // ── ② 상한 3 (J17 · #444 X1) ────────────────────────────────────────────────

    @Test
    fun `네 번째 칸은 제약 위반으로 죽는다 — 상한 3을 DB 가 지킨다`() {
        val boardId = seedBoard("CARDMAX", "상한 판정용 보드")
        (0..2).forEach { insertLayoutField(boardId, "BOARD", it, "field_$it") }

        assertThat(sqlStateOf { insertLayoutField(boardId, "BOARD", 3, "field_3") })
            .isEqualTo(CHECK_VIOLATION)
    }

    @Test
    fun `음수 position 도 제약 위반이다`() {
        val boardId = seedBoard("CARDNEG", "음수 position 판정용 보드")

        assertThat(sqlStateOf { insertLayoutField(boardId, "BOARD", -1, "field_neg") })
            .isEqualTo(CHECK_VIOLATION)
    }

    @Test
    fun `view_scope 는 BOARD 와 BACKLOG 만 받는다`() {
        val boardId = seedBoard("CARDSCOPE", "view_scope 판정용 보드")

        assertThat(sqlStateOf { insertLayoutField(boardId, "BOGUS", 0, "field_bogus") })
            .isEqualTo(CHECK_VIOLATION)
    }

    // ── FK · CASCADE (DATA.md §7 · 조인 테이블 CASCADE 규약) ─────────────────────

    @Test
    fun `board_id FK 가 boards 를 ON DELETE CASCADE 로 참조한다`() {
        assertThat(foreignKeyTarget("board_card_layout_fields", "board_id"))
            .isEqualTo("boards" to "CASCADE")
    }

    @Test
    fun `FK 인덱스가 board_id 를 leftmost 로 덮는다`() {
        // DATA.md §7 — PostgreSQL 은 FK 인덱스를 자동 생성하지 않는다.
        // 복합 PK 의 leftmost prefix(board_id)가 그 역할을 하므로 별도 인덱스를 두지 않는다
        // (board_non_working_dates · V500:36-38 과 같은 판단).
        assertThat(indexDefs("board_card_layout_fields"))
            .anyMatch { it.substringAfter("USING btree (").startsWith("board_id") }
    }

    @Test
    fun `보드를 지우면 카드 레이아웃 행이 함께 사라진다`() {
        val boardId = seedBoard("CARDCASCADE", "CASCADE 판정용 보드")
        insertLayoutField(boardId, "BOARD", 0, "assignee")

        conn().use { c ->
            c.prepareStatement("DELETE FROM boards WHERE id = ?").use { ps ->
                ps.setObject(1, boardId)
                ps.executeUpdate()
            }
        }

        assertThat(fieldKeysOf(boardId, "BOARD")).isEmpty()
    }
}
