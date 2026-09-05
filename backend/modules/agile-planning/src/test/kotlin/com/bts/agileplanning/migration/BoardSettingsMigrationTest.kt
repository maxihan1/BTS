// V509 검증 — boards 설정 3칸(time_tracking·working_days·board_timezone) + board_non_working_dates 신설

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
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/**
 * CHECK 제약 위반 SQLSTATE.
 *
 * ★ 「아무 SQLException」으로 재면 **테이블이 아예 없을 때(42P01)도 통과한다** — 부정 단언이
 * 공허해진다. 형제 [BoardCardLayoutSchemaTest] · [BoardDetailViewSchemaTest] 와 같은 양식으로
 * SQLSTATE 값까지 못 박는다.
 */
private const val CHECK_VIOLATION = "23514"

/**
 * `V509__board_settings_tabs.sql` 을 검증한다 (스펙 R5·R6 · 갭 D · J38·J39·J40).
 *
 * 「작업일」·「추정」 탭이 저장할 칸을 `boards` 에 세 개 얹고, 비근무일 목록을 별도 테이블로 낸다.
 *
 * ## 왜 전용 컨테이너 + 3단 부팅인가
 * `AgilePlanningTestcontainersConfig` 의 공유 컨테이너는 무조건 최신까지 밀어버려 「특정 버전
 * 시점에 데이터 심기」가 불가능하다. `V508` 선례([BoardColumnStatesMigrationTest])와 같은 이유로
 * `@Container` 로 전용 컨테이너를 띄우고 `target("508")` → 픽스처 → `target(null)` 로 나눈다.
 * 마지막 단계에 숫자를 박지 않는 것도 그 선례를 따른다 — `V510` 이 들어와도 체인 전체가 돈다.
 *
 * ## 이 클래스가 지는 판정 4축
 *
 * | 축 | 무엇 | 근거 |
 * |---|---|---|
 * | ① 칸 3개 | 타입·NULL 허용·기본값이 설계대로다 | R5 |
 * | ② **무변경 보존** | V509 **이전에 있던** 보드의 근무일이 NULL 로 남는다 | **R6** |
 * | ③ 비근무일 테이블 | 복합 PK · FK CASCADE · FK 인덱스 | J39 · DATA.md §7 |
 * | ④ CASCADE 실측 | 보드를 지우면 고아 행이 안 남는다 | 조인 테이블 CASCADE 규약 |
 * | ⑤ **허용값** | `time_tracking` 이 2종만 받는다 | **J36 · 형제 `board_type`** |
 *
 * ★ **② 가 이 클래스에서 가장 중요하다.** `working_days` 를 `NOT NULL DEFAULT '{MON..FRI}'` 로
 * 두면 배포 순간 **기존 모든 스프린트의 번다운이 바뀐다**(스펙 R6 · 데이터 모델 절의 ★).
 * NULL 이 「미설정 = 달력일 전부 = 현행 유지」다. ② 의 두 단언(NULL 로 남는다 / 기본값이 없다)이
 * 그 잘못된 구현을 red 로 만든다 — 없으면 조용히 통과한다.
 *
 * ★ `time_tracking` 만 `NOT NULL DEFAULT 'NONE'` 인 이유는 그 기본값이 **현행 동작 그 자체**라서다.
 * 지금 어떤 보드도 시간 추적을 하지 않으므로 `NONE` 백필은 관측 가능한 변화를 만들지 않는다.
 *
 * ★ 그 `time_tracking` 의 **허용값은 DB CHECK 가 지킨다**(⑤). 형제 `board_type`(`V505:11`)이 같은 모양의
 * 닫힌 열거형을 `boards_board_type_allowed` 로 지키고 있어, 같은 테이블의 같은 종류 칸 둘이
 * 서로 다른 규율을 받지 않게 맞춘 것이다 — 판정 근거는 스펙이 아니라 **형제 칸**이다.
 *
 * 참조. `docs/specs/2026-09-05-board-settings-remaining-tabs-177.md` §데이터 모델 · R5·R6 ·
 * `docs/plans/2026-09-05-board-settings-remaining-tabs-177.md` Task 1 · DATA.md §4·§4.1·§7.
 */
@Testcontainers
class BoardSettingsMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_agileplanning_board_settings_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /** `V509` 적용 **전에** 심은 보드. ② 무변경 보존의 기준선이다. */
        private lateinit var legacyBoardId: UUID

        private fun flyway(target: String?) =
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/agile-planning")
                .let { if (target == null) it else it.target(target) }
                .load()

        /**
         * 3단 부팅 — `V508` 까지 올린 뒤 보드를 심고 나머지 체인을 태운다.
         *
         * 픽스처를 `V508` **뒤에** 심어야 「V509 이전부터 있던 보드」가 만들어진다.
         * 전체를 먼저 밀고 심으면 ② 가 잴 것이 없어진다 — 새 행은 어차피 기본값을 받는다.
         */
        @BeforeAll
        @JvmStatic
        fun migrateWithSeedBeforeV509() {
            flyway("508").migrate()
            legacyBoardId = conn().use { c -> seedBoard(c, "LEGACY", "V509 이전부터 있던 보드") }
            flyway(null).migrate()
        }

        private fun seedBoard(
            c: Connection,
            projectKey: String,
            name: String,
        ): UUID {
            val id = UUID.randomUUID()
            c.prepareStatement(
                "INSERT INTO boards (id, project_key, name) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, projectKey)
                ps.setString(3, name)
                ps.executeUpdate()
            }
            return id
        }

        fun conn(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    // ── 헬퍼 — 전부 prepared statement (SQL 문자열 결합 금지) ────────────────────

    /** `format_type` 으로 선언 타입을 원문 그대로 읽는다 — `character varying(3)[]` 처럼 길이까지 본다. */
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

    @Suppress("NestedBlockDepth")
    private fun columnMeta(
        table: String,
        column: String,
    ): Pair<String, String?>? =
        conn().use { c ->
            c.prepareStatement(
                "SELECT is_nullable, column_default FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { ps ->
                ps.setString(1, table)
                ps.setString(2, column)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) to rs.getString(2) else null
                }
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
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) to rs.getString(2) else null
                }
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

    private fun insertNonWorkingDate(
        boardId: UUID,
        date: LocalDate,
    ) {
        conn().use { c ->
            c.prepareStatement("INSERT INTO board_non_working_dates (board_id, date) VALUES (?, ?)").use { ps ->
                ps.setObject(1, boardId)
                ps.setObject(2, date)
                ps.executeUpdate()
            }
        }
    }

    private fun updateTimeTracking(
        boardId: UUID,
        value: String,
    ) {
        conn().use { c ->
            c.prepareStatement("UPDATE boards SET time_tracking = ? WHERE id = ?").use { ps ->
                ps.setString(1, value)
                ps.setObject(2, boardId)
                ps.executeUpdate()
            }
        }
    }

    @Suppress("NestedBlockDepth")
    private fun timeTrackingOf(boardId: UUID): String? =
        conn().use { c ->
            c.prepareStatement("SELECT time_tracking FROM boards WHERE id = ?").use { ps ->
                ps.setObject(1, boardId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    /** 테이블에 걸린 CHECK 제약 이름 목록. */
    private fun checkConstraintNames(table: String): List<String> =
        queryStrings(
            """
            SELECT c.conname
            FROM pg_constraint c
            JOIN pg_class t ON c.conrelid = t.oid
            JOIN pg_namespace n ON t.relnamespace = n.oid
            WHERE n.nspname = 'public' AND t.relname = ? AND c.contype = 'c'
            """.trimIndent(),
            table,
        )

    /** [block] 이 던진 SQLSTATE. 죽지 **않으면** null 이라 「통과해 버렸다」가 그대로 드러난다. */
    private fun sqlStateOf(block: () -> Unit): String? =
        try {
            block()
            null
        } catch (e: SQLException) {
            e.sqlState
        }

    // ── ① 칸 3개 (R5) ──────────────────────────────────────────────────────────

    @Test
    fun `boards 에 time_tracking working_days board_timezone 세 칸이 생긴다`() {
        assertThat(columnsOf("boards"))
            .contains("time_tracking", "working_days", "board_timezone")
    }

    @Test
    fun `time_tracking 은 varchar(24) NOT NULL 이고 기본값이 NONE 이다`() {
        assertThat(declaredType("boards", "time_tracking")).isEqualTo("character varying(24)")

        val (nullable, default) = requireNotNull(columnMeta("boards", "time_tracking"))
        assertThat(nullable).isEqualTo("NO")
        // 기본값이 곧 현행 동작이라 백필이 관측 가능한 변화를 만들지 않는다.
        assertThat(default).contains("'NONE'")
    }

    @Test
    fun `working_days 는 varchar(3) 배열이고 NULL 을 허용한다`() {
        assertThat(declaredType("boards", "working_days")).isEqualTo("character varying(3)[]")

        val (nullable, _) = requireNotNull(columnMeta("boards", "working_days"))
        assertThat(nullable).isEqualTo("YES")
    }

    @Test
    fun `board_timezone 은 varchar(64) 이고 NULL 을 허용한다`() {
        assertThat(declaredType("boards", "board_timezone")).isEqualTo("character varying(64)")

        val (nullable, _) = requireNotNull(columnMeta("boards", "board_timezone"))
        assertThat(nullable).isEqualTo("YES")
    }

    // ── ② 무변경 보존 — 이 클래스의 핵심 (R6) ────────────────────────────────────

    @Test
    fun `working_days 와 board_timezone 에 기본값이 없다 — 배포가 기존 번다운을 바꾸지 않는다`() {
        // NOT NULL DEFAULT '{MON..FRI}' 로 두면 기존 모든 스프린트의 번다운이 배포 순간 바뀐다.
        // 이 단언이 없으면 그 구현이 조용히 통과한다.
        assertThat(requireNotNull(columnMeta("boards", "working_days")).second).isNull()
        assertThat(requireNotNull(columnMeta("boards", "board_timezone")).second).isNull()
    }

    @Test
    fun `V509 이전부터 있던 보드는 근무일과 타임존이 NULL 로 남는다`() {
        val (workingDays, timezone) =
            conn().use { c ->
                c.prepareStatement("SELECT working_days, board_timezone FROM boards WHERE id = ?").use { ps ->
                    ps.setObject(1, legacyBoardId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject("working_days") to rs.getString("board_timezone")
                    }
                }
            }

        assertThat(workingDays).isNull()
        assertThat(timezone).isNull()
    }

    @Test
    fun `V509 이전부터 있던 보드의 time_tracking 이 NONE 으로 백필된다`() {
        val value =
            conn().use { c ->
                c.prepareStatement("SELECT time_tracking FROM boards WHERE id = ?").use { ps ->
                    ps.setObject(1, legacyBoardId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            }

        assertThat(value).isEqualTo("NONE")
    }

    // ── ③ 비근무일 테이블 (J39 · DATA.md §7) ────────────────────────────────────

    @Test
    fun `board_non_working_dates 가 board_id 와 date 두 칸으로 생긴다`() {
        assertThat(columnsOf("board_non_working_dates"))
            .containsExactlyInAnyOrder("board_id", "date")
        assertThat(declaredType("board_non_working_dates", "board_id")).isEqualTo("uuid")
        assertThat(declaredType("board_non_working_dates", "date")).isEqualTo("date")
    }

    @Test
    fun `기본키가 board_id 와 date 복합이다`() {
        assertThat(primaryKeyColumns("board_non_working_dates"))
            .containsExactly("board_id", "date")
    }

    @Test
    fun `같은 보드에 같은 날짜를 두 번 등록하면 거부된다`() {
        val boardId = conn().use { c -> seedBoard(c, "DUPDATE", "중복 날짜 판정용 보드") }
        insertNonWorkingDate(boardId, LocalDate.of(2026, 10, 3))

        assertThatThrownBy { insertNonWorkingDate(boardId, LocalDate.of(2026, 10, 3)) }
            .isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `board_id FK 가 boards 를 ON DELETE CASCADE 로 참조한다`() {
        assertThat(foreignKeyTarget("board_non_working_dates", "board_id"))
            .isEqualTo("boards" to "CASCADE")
    }

    @Test
    fun `FK 인덱스가 board_id 를 leftmost 로 덮는다`() {
        // DATA.md §7 — PostgreSQL 은 FK 인덱스를 자동 생성하지 않는다.
        // 복합 PK (board_id, date) 의 leftmost prefix 가 그 역할을 하므로 별도 인덱스를 두지 않는다.
        assertThat(indexDefs("board_non_working_dates"))
            .anyMatch { it.contains("(board_id, date)") }
    }

    // ── ④ CASCADE 실측 (조인 테이블 CASCADE 규약) ────────────────────────────────

    @Test
    fun `보드를 지우면 비근무일 행이 함께 사라진다`() {
        val boardId = conn().use { c -> seedBoard(c, "CASCADE", "CASCADE 판정용 보드") }
        insertNonWorkingDate(boardId, LocalDate.of(2026, 1, 1))

        conn().use { c ->
            c.prepareStatement("DELETE FROM boards WHERE id = ?").use { ps ->
                ps.setObject(1, boardId)
                ps.executeUpdate()
            }
        }

        val orphans =
            conn().use { c ->
                c.prepareStatement("SELECT count(*) FROM board_non_working_dates WHERE board_id = ?").use { ps ->
                    ps.setObject(1, boardId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        assertThat(orphans).isZero()
    }

    // ── ⑤ time_tracking 허용값 — 형제 `board_type` 과 같은 관용구 (J36) ───────

    @Test
    fun `time_tracking 에 그 밖의 값을 넣으면 CHECK 위반으로 죽는다`() {
        val boardId = conn().use { c -> seedBoard(c, "TTBOGUS", "허용값 판정용 보드") }

        // ★ SQLSTATE 값 동등으로 고정한다. isInstanceOf(SQLException) 로 두면
        //   테이블·칸 부재(42P01·42703)까지 삼켜 단언이 공허해진다.
        assertThat(sqlStateOf { updateTimeTracking(boardId, "BOGUS") })
            .isEqualTo(CHECK_VIOLATION)
    }

    @Test
    fun `NONE 과 REMAINING_AND_SPENT 는 각각 들어간다`() {
        // ★ 위 부정 단언의 **대조군**이다. 「BOGUS 는 죽는다」만 두면
        //   IN ('NONE') 처럼 너무 좁거나 아예 전부를 죽이는 제약도 그대로 통과한다.
        val boardId = conn().use { c -> seedBoard(c, "TTALLOWED", "대조군 보드") }

        listOf("NONE", "REMAINING_AND_SPENT").forEach { allowed ->
            updateTimeTracking(boardId, allowed)
            assertThat(timeTrackingOf(boardId)).isEqualTo(allowed)
        }
    }

    @Test
    fun `제약 이름이 형제 board_type 의 관용구를 따른다`() {
        // V505:11 이 boards_board_type_allowed 로 잡은 <테이블>_<칸>_allowed 형식을 그대로 쓴다.
        // 둘을 한 단언에 두는 이유는 이름이 곧 계약이기 때문이다 —
        // 멱등 래퍼(plan Task 4)가 pg_constraint 를 이 이름으로 찾는다.
        assertThat(checkConstraintNames("boards"))
            .contains("boards_time_tracking_allowed", "boards_board_type_allowed")
    }
}
