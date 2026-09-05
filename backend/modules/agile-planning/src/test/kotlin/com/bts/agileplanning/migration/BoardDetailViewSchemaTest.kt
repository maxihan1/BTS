// V509 검증 — board_detail_view_fields 는 필드를 그룹 4종으로만 나눠 담는다(J47 · DC 근거는 편차 X10)

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
 * 공허해진다. [BoardCardLayoutSchemaTest] 가 red 단계 실측으로 확인한 함정이라 값 동등으로 못 박는다.
 */
private const val CHECK_VIOLATION = "23514"

/** 상세 보기 필드 그룹 4종 — J47 원문의 General fields · Date fields · People · Links. */
private val FIELD_GROUPS = listOf("GENERAL", "DATE", "PEOPLE", "LINKS")

/**
 * `V509__board_settings_tabs.sql` 의 `board_detail_view_fields` 를 검증한다 (갭 E · J47·J48).
 *
 * ## 판정은 **한 쌍**이다 — 한쪽만 두면 잘못된 스키마가 통과한다
 *
 * | 축 | 무엇을 재는가 | 이 축이 없으면 통과해 버리는 잘못된 스키마 |
 * |---|---|---|
 * | ① 유효 4종 | `GENERAL`·`DATE`·`PEOPLE`·`LINKS` 가 **각각** 들어간다 | 그룹을 전부 거부하는(또는 일부만 허용하는) CHECK |
 * | ② 그 밖은 거부 | `BOGUS` 가 **CHECK 위반(23514)** 으로 죽는다 | 제약이 아예 없는 스키마 |
 *
 * ★ ① 은 네 그룹에 모두 `position = 0` 을 넣는다. `field_group` 이 PK 에 들어 있어야만 성립하므로
 * 「그룹마다 자리가 따로다」(J48 의 그룹 내 순서)까지 같은 단언이 덮는다.
 *
 * ★ ② 를 SQLSTATE **값 동등**으로 재는 이유가 공허 방지 그 자체다. `isInstanceOf(SQLException)` 으로
 * 두면 테이블이 없는 red 단계에서도 초록이 되어 「제약이 생겼다」를 한 번도 재지 못한다.
 *
 * ## 왜 전용 컨테이너인가
 * `AgilePlanningTestcontainersConfig` 의 공유 컨테이너는 무조건 최신까지 밀어버린다. 스키마
 * 판정은 체인 전체를 태운 결과만 보면 되므로 전용 컨테이너에 `target` 없이 한 번에 올린다
 * (`V510` 이 들어와도 그대로 돈다 — [BoardCardLayoutSchemaTest] 와 같은 이유로 숫자를 박지 않는다).
 *
 * 참조. `docs/specs/2026-09-05-board-settings-remaining-tabs-177.md` §데이터 모델 · R7 · 편차 X10 ·
 * `docs/plans/2026-09-05-board-settings-remaining-tabs-177.md` Task 3 · DATA.md §4·§4.1·§7.
 */
@Testcontainers
class BoardDetailViewSchemaTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_agileplanning_detail_view_test")
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

    private fun insertDetailField(
        boardId: UUID,
        fieldGroup: String,
        position: Int,
        fieldKey: String,
    ) {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO board_detail_view_fields (board_id, field_group, position, field_key)" +
                    " VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, boardId)
                ps.setString(2, fieldGroup)
                ps.setShort(3, position.toShort())
                ps.setString(4, fieldKey)
                ps.executeUpdate()
            }
        }
    }

    /** 한 보드·한 그룹의 field_key 를 position 순으로 읽는다. */
    @Suppress("NestedBlockDepth")
    private fun fieldKeysOf(
        boardId: UUID,
        fieldGroup: String,
    ): List<String> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT field_key FROM board_detail_view_fields" +
                    " WHERE board_id = ? AND field_group = ? ORDER BY position",
            ).use { ps ->
                ps.setObject(1, boardId)
                ps.setString(2, fieldGroup)
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

    // ── 구조 (J47 · DATA.md §7) ─────────────────────────────────────────────────

    @Test
    fun `board_detail_view_fields 가 네 칸으로 생긴다`() {
        assertThat(columnsOf("board_detail_view_fields"))
            .containsExactlyInAnyOrder("board_id", "field_group", "position", "field_key")
        assertThat(declaredType("board_detail_view_fields", "board_id")).isEqualTo("uuid")
        assertThat(declaredType("board_detail_view_fields", "field_group")).isEqualTo("character varying(16)")
        assertThat(declaredType("board_detail_view_fields", "position")).isEqualTo("smallint")
        assertThat(declaredType("board_detail_view_fields", "field_key")).isEqualTo("character varying(128)")
    }

    @Test
    fun `기본키가 board_id field_group position 복합이다`() {
        assertThat(primaryKeyColumns("board_detail_view_fields"))
            .containsExactly("board_id", "field_group", "position")
    }

    // ── ① 유효 4종 — 대조군 (J47) ───────────────────────────────────────────────

    @Test
    fun `그룹 4종은 각각 저장된다`() {
        val boardId = seedBoard("DETAILOK", "그룹 4종 판정용 보드")

        // 네 그룹 모두 position 0 — field_group 이 PK 에 있어야만 성립한다(그룹마다 자리가 따로다).
        FIELD_GROUPS.forEach { group ->
            assertThat(sqlStateOf { insertDetailField(boardId, group, 0, "field_${group.lowercase()}") })
                .describedAs("유효 그룹 %s 는 저장돼야 한다", group)
                .isNull()
        }

        // 되읽어 그룹별로 제 값이 남았는지 본다 — 일부러 그룹마다 다른 key 를 넣었다.
        FIELD_GROUPS.forEach { group ->
            assertThat(fieldKeysOf(boardId, group))
                .describedAs("그룹 %s 의 필드", group)
                .containsExactly("field_${group.lowercase()}")
        }
    }

    @Test
    fun `한 그룹 안에서 순서대로 여러 필드를 담는다`() {
        // J48 — 상세 보기는 그룹 안에서 드래그로 순서를 바꾼다. 카드 레이아웃과 달리 개수 상한이 없다.
        val boardId = seedBoard("DETAILORDER", "그룹 내 순서 판정용 보드")
        val keys = listOf("summary", "status", "cf_severity", "description")

        keys.forEachIndexed { i, key -> insertDetailField(boardId, "GENERAL", i, key) }

        assertThat(fieldKeysOf(boardId, "GENERAL")).containsExactlyElementsOf(keys)
    }

    // ── ② 그 밖은 거부 (J47 · 공허 단언 방지) ───────────────────────────────────

    @Test
    fun `field_group 은 그룹 4종만 받는다`() {
        val boardId = seedBoard("DETAILBOGUS", "그룹 위반 판정용 보드")

        assertThat(sqlStateOf { insertDetailField(boardId, "BOGUS", 0, "field_bogus") })
            .isEqualTo(CHECK_VIOLATION)
    }

    @Test
    fun `카드 레이아웃의 view_scope 값은 field_group 으로 받지 않는다`() {
        // 형제 테이블에서 복사해 온 값(BOARD/BACKLOG)이 흘러들어도 DB 가 막는다.
        val boardId = seedBoard("DETAILSCOPE", "타 테이블 값 유입 판정용 보드")

        assertThat(sqlStateOf { insertDetailField(boardId, "BOARD", 0, "field_scope") })
            .isEqualTo(CHECK_VIOLATION)
    }

    // ── FK · CASCADE (DATA.md §7 · 조인 테이블 CASCADE 규약) ─────────────────────

    @Test
    fun `board_id FK 가 boards 를 ON DELETE CASCADE 로 참조한다`() {
        assertThat(foreignKeyTarget("board_detail_view_fields", "board_id"))
            .isEqualTo("boards" to "CASCADE")
    }

    @Test
    fun `FK 인덱스가 board_id 를 leftmost 로 덮는다`() {
        // DATA.md §7 — PostgreSQL 은 FK 인덱스를 자동 생성하지 않는다.
        // 복합 PK 의 leftmost prefix(board_id)가 그 역할을 하므로 별도 인덱스를 두지 않는다
        // (board_card_layout_fields · V500:36-38 과 같은 판단).
        assertThat(indexDefs("board_detail_view_fields"))
            .anyMatch { it.substringAfter("USING btree (").startsWith("board_id") }
    }

    @Test
    fun `보드를 지우면 상세 보기 필드 행이 함께 사라진다`() {
        val boardId = seedBoard("DETAILCASCADE", "CASCADE 판정용 보드")
        insertDetailField(boardId, "PEOPLE", 0, "assignee")

        conn().use { c ->
            c.prepareStatement("DELETE FROM boards WHERE id = ?").use { ps ->
                ps.setObject(1, boardId)
                ps.executeUpdate()
            }
        }

        assertThat(fieldKeysOf(boardId, "PEOPLE")).isEmpty()
    }
}
