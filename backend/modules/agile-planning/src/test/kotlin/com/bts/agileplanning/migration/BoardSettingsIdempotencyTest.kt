// V509 재실행 판정 — 같은 마이그레이션 SQL 을 다시 태워도 죽지 않고 스키마가 움직이지 않는지 잰다

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

/** V509 파일의 클래스패스 경로. Flyway 가 읽는 것과 **같은 파일**이어야 재실행 판정이 의미를 갖는다. */
private const val V509_RESOURCE = "/db/migration/agile-planning/V509__board_settings_tabs.sql"

/**
 * `V509__board_settings_tabs.sql` 의 **재실행 안전성**을 잰다 (plan Task 4 · 부채 161 · `#444`).
 *
 * ## 왜 이 판정이 따로 있나
 *
 * Flyway 는 적용된 마이그레이션을 다시 돌리지 않으므로 「재실행하면 죽는다」는 평소에 보이지 않는다.
 * 그러나 복구·재해 훈련·부분 적용 뒤 손으로 다시 태우는 자리에서 그대로 드러나고, 그때는
 * `ADD COLUMN` 하나가 `42701` 로 죽으면서 **뒤따르는 문 전부가 적용되지 않는다.**
 * `#444` 가 `V506` 에서 밟은 자리이며, 그 처방이 `IF NOT EXISTS` 와
 * `ADD CONSTRAINT` 를 감싸는 `DO $$ ... pg_constraint ... $$` 다
 * (★`ADD CONSTRAINT` 에는 `IF NOT EXISTS` 문법이 **없다**).
 *
 * ## 이 클래스가 지는 판정 축
 *
 * | 축 | 무엇 | 왜 |
 * |---|---|---|
 * | ① 재실행 1차 | 파일의 **모든 문**이 실패 없이 다시 돈다 | 부채 161 |
 * | ② 재실행 2차 | 한 번 더 돌려도 실패가 없다 | 1차만 특별하지 않음을 확인 |
 * | ③ 멱등 | **1차 재실행 후 ↔ 2차 재실행 후** 스키마가 같다 | `#444` 실측 |
 * | ④ 비-공허 짝 | V509 가 실제로 3칸·3테이블·제약 1개를 더했다 | ③ 이 공허해지는 것을 막는다 |
 *
 * ★ ③ 의 기준선이 「적용 전」이 아니라 **「1차 재실행 후」**인 것이 핵심이다.
 * 「적용 전 ↔ 1차 후」로 재면 앞선 단계가 만든 행을 1차 재실행이 **정당하게** 백필하는 것까지
 * 결함으로 오판한다(`#444` 실측). 멱등은 「두 번째부터 아무것도 바뀌지 않는다」이지
 * 「한 번도 바뀌지 않는다」가 아니다.
 *
 * ★ ①②③ 는 **서로를 가리지 않는다.** ①② 가 죽으면 스키마가 움직이지 않아 ③ 이 그대로 통과하므로,
 * ③ 만 두면 「전부 실패해서 아무것도 안 바뀐 상태」가 멱등으로 읽힌다. 실패 목록을 문 단위로
 * 모아 ①② 로 따로 세우는 이유가 그것이다.
 *
 * ## 왜 전용 컨테이너 + 2단 부팅인가
 * 공유 컨테이너는 무조건 최신까지 밀어버려 「V509 적용 **전**」 스냅숏을 찍을 수 없다.
 * 형제 [BoardSettingsMigrationTest] 와 같은 이유로 `@Container` 로 전용 컨테이너를 띄운다.
 *
 * ★ 마지막 단계를 `target(null)` 이 아니라 **`target("509")`** 로 못 박는다. 형제들과 갈리는데,
 * 이 클래스가 재는 것은 「체인이 끝까지 돈다」가 아니라 **「V509 라는 한 파일의 적용 전/후 차이」**라서다.
 * `target(null)` 로 두면 나중에 들어올 `V510` 의 변경이 그 차이에 섞여 판정이 흐려진다.
 *
 * 참조. `docs/plans/2026-09-05-board-settings-remaining-tabs-177.md` Task 4 · DATA.md §4.1.
 */
@Testcontainers
class BoardSettingsIdempotencyTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_agileplanning_v509_idempotency_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /** V509 파일을 문 단위로 쪼갠 것. 0건이 되면 ①②③ 이 전부 공허해지므로 ⑤ 가 짝으로 지킨다. */
        private lateinit var statements: List<String>

        private lateinit var beforeV509: List<String>
        private lateinit var afterApply: List<String>
        private lateinit var afterReplay1: List<String>
        private lateinit var afterReplay2: List<String>

        /** `"<SQLSTATE> @ <문 앞머리>"` 목록. 비어 있어야 재실행이 성공한 것이다. */
        private lateinit var replay1Failures: List<String>
        private lateinit var replay2Failures: List<String>

        private fun flyway(target: String?) =
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/agile-planning")
                .let { if (target == null) it else it.target(target) }
                .load()

        /**
         * V508 → 스냅숏 → V509 → 재실행 1차 → 스냅숏 → 재실행 2차 → 스냅숏.
         *
         * 전 과정을 `@BeforeAll` 에 몰아 넣고 `@Test` 는 **찍어 둔 스냅숏만 비교**한다.
         * 재실행이 스키마를 건드리므로 테스트 메서드 실행 순서에 판정이 의존하면 안 된다.
         */
        @BeforeAll
        @JvmStatic
        fun applyThenReplayTwice() {
            statements = splitStatements(readV509())

            flyway("508").migrate()
            beforeV509 = schemaSnapshot()

            flyway("509").migrate()
            afterApply = schemaSnapshot()

            replay1Failures = runStatements(statements)
            afterReplay1 = schemaSnapshot()

            replay2Failures = runStatements(statements)
            afterReplay2 = schemaSnapshot()
        }

        fun conn(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

        private fun readV509(): String =
            checkNotNull(BoardSettingsIdempotencyTest::class.java.getResourceAsStream(V509_RESOURCE)) {
                "$V509_RESOURCE 를 클래스패스에서 찾지 못했다 — 재실행 판정이 잴 대상이 없다"
            }.use { it.readBytes().toString(Charsets.UTF_8) }

        // ── 재실행 ────────────────────────────────────────────────────────────

        /**
         * 문을 **하나씩 autocommit 으로** 실행하고 실패를 모은다.
         *
         * ★ 전체를 한 트랜잭션으로 묶으면 첫 실패에서 멈춰 **뒤의 실패가 보이지 않는다** —
         * 「`ADD COLUMN` 이 죽었다」와 「`ADD CONSTRAINT` 가 죽었다」가 한 메시지에 섞여
         * 두 축이 서로를 가린다. 문 단위 실행이라야 실패 목록이 원인을 그대로 가리킨다.
         */
        @Suppress("NestedBlockDepth")
        private fun runStatements(stmts: List<String>): List<String> {
            val failures = mutableListOf<String>()
            conn().use { c ->
                c.autoCommit = true
                stmts.forEach { s ->
                    try {
                        c.createStatement().use { st -> st.execute(s) }
                    } catch (e: SQLException) {
                        failures.add("${e.sqlState} @ ${headOf(s)}")
                    }
                }
            }
            return failures
        }

        private fun headOf(statement: String): String = statement.replace(Regex("\\s+"), " ").take(90)

        // ── 스키마 스냅숏 ─────────────────────────────────────────────────────

        /**
         * `public` 스키마 전체를 문자열 목록으로 찍는다 — 컬럼(타입·NOT NULL·기본값) · 제약 · 인덱스.
         *
         * ★ 대상을 V509 가 건드린 객체로 좁히지 **않는다.** 좁히면 「V509 가 몰래 더한 무언가」를
         * 판정이 아예 보지 못한다. `flyway_schema_history` 만 뺀다 — 적용 이력은 스키마가 아니다.
         */
        private fun schemaSnapshot(): List<String> =
            conn().use { c ->
                (rows(c, COLUMNS_SQL) + rows(c, CONSTRAINTS_SQL) + rows(c, INDEXES_SQL)).sorted()
            }

        private fun rows(
            c: Connection,
            sql: String,
        ): List<String> =
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val out = mutableListOf<String>()
                    while (rs.next()) out.add(rs.getString(1))
                    out
                }
            }

        private val COLUMNS_SQL =
            """
            SELECT 'col ' || t.relname || '.' || a.attname
                   || ' :: ' || format_type(a.atttypid, a.atttypmod)
                   || ' notnull=' || a.attnotnull
                   || ' default=' || coalesce(pg_get_expr(d.adbin, d.adrelid), '<none>')
            FROM pg_attribute a
            JOIN pg_class t ON a.attrelid = t.oid
            JOIN pg_namespace n ON t.relnamespace = n.oid
            LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            WHERE n.nspname = 'public' AND t.relkind = 'r'
              AND t.relname <> 'flyway_schema_history'
              AND a.attnum > 0 AND NOT a.attisdropped
            """.trimIndent()

        private val CONSTRAINTS_SQL =
            """
            SELECT 'con ' || t.relname || '.' || c.conname || ' :: ' || pg_get_constraintdef(c.oid)
            FROM pg_constraint c
            JOIN pg_class t ON c.conrelid = t.oid
            JOIN pg_namespace n ON t.relnamespace = n.oid
            WHERE n.nspname = 'public' AND t.relname <> 'flyway_schema_history'
            """.trimIndent()

        private val INDEXES_SQL =
            """
            SELECT 'idx ' || tablename || '.' || indexname || ' :: ' || indexdef
            FROM pg_indexes
            WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
            """.trimIndent()

        // ── SQL 문 분할 ───────────────────────────────────────────────────────

        /**
         * SQL 을 세미콜론으로 쪼개되 **주석 · 문자열 리터럴 · 달러 인용 안의 세미콜론에 속지 않는다.**
         *
         * ★ 달러 인용 처리가 이 판정의 전제다. `DO $$ ... $$` 안에는 세미콜론이 들어 있어서,
         * 그것을 모르는 분할기는 `DO` 블록을 조각내 **문법 오류를 「멱등 실패」로 오보**한다.
         */
        @Suppress("NestedBlockDepth", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
        fun splitStatements(sql: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var i = 0
            while (i < sql.length) {
                val c = sql[i]
                val tag = dollarTagAt(sql, i)
                when {
                    c == '-' && sql.startsWith("--", i) -> {
                        while (i < sql.length && sql[i] != '\n') i++
                    }

                    c == '\'' -> {
                        cur.append(c)
                        i++
                        while (i < sql.length) {
                            cur.append(sql[i])
                            if (sql[i] == '\'') {
                                // '' 는 문자열 **안의** 작은따옴표다 — 여기서 닫히지 않는다.
                                if (i + 1 < sql.length && sql[i + 1] == '\'') {
                                    cur.append('\'')
                                    i += 2
                                    continue
                                }
                                i++
                                break
                            }
                            i++
                        }
                    }

                    tag != null -> {
                        val end = sql.indexOf(tag, i + tag.length)
                        check(end >= 0) { "달러 인용이 닫히지 않았다: $tag" }
                        cur.append(sql, i, end + tag.length)
                        i = end + tag.length
                    }

                    c == ';' -> {
                        out.add(cur.toString().trim())
                        cur.clear()
                        i++
                    }

                    else -> {
                        cur.append(c)
                        i++
                    }
                }
            }
            if (cur.isNotBlank()) out.add(cur.toString().trim())
            return out.filter { it.isNotBlank() }
        }

        /** `i` 에서 시작하는 달러 인용 태그(`$$` · `$tag$`). 아니면 null. */
        private fun dollarTagAt(
            sql: String,
            i: Int,
        ): String? {
            if (sql[i] != DOLLAR) return null
            var j = i + 1
            while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
            return if (j < sql.length && sql[j] == DOLLAR) sql.substring(i, j + 1) else null
        }

        private const val DOLLAR = '$'
    }

    /** V509 가 더한 것. `④` 의 대조군이자 되돌리기 판정의 기준이다. */
    private val addedByV509: List<String> get() = afterApply - beforeV509.toSet()

    // ── ① · ② 재실행이 죽지 않는다 (부채 161 · #444) ──────────────────────────

    @Test
    fun `V509 의 모든 문을 다시 실행해도 한 문도 실패하지 않는다`() {
        // ADD COLUMN → IF NOT EXISTS, CREATE TABLE → IF NOT EXISTS,
        // ADD CONSTRAINT → DO $$ ... pg_constraint ... $$ (IF NOT EXISTS 문법이 없다).
        // 실패 목록은 "<SQLSTATE> @ <문 앞머리>" 라 어느 처방이 빠졌는지 그대로 읽힌다.
        assertThat(replay1Failures).isEmpty()
    }

    @Test
    fun `한 번 더 실행해도 실패하지 않는다 — 1차만 특별하지 않다`() {
        assertThat(replay2Failures).isEmpty()
    }

    // ── ③ 멱등 — 1차 재실행 후 ↔ 2차 재실행 후 (#444 실측) ──────────────────────

    @Test
    fun `1차 재실행 후와 2차 재실행 후의 스키마가 같다`() {
        // ★ 기준선이 「적용 전」이 아니다. 「적용 전 ↔ 1차 후」로 재면 1차가 정당하게 하는
        //   백필까지 결함으로 오판한다(#444 실측).
        assertThat(afterReplay2).isEqualTo(afterReplay1)
    }

    // ── ④ 비-공허 짝 — V509 가 실제로 무언가를 더했다 ──────────────────────────

    @Test
    fun `V509 는 boards 3칸과 새 테이블 3개와 제약 1개를 더한다`() {
        // 이 단언이 없으면 ③ 은 「V509 가 아무것도 안 했다」에서도 통과한다.
        assertThat(addedByV509.filter { it.startsWith("col boards.") }.map { it.substringBefore(" :: ") })
            .containsExactlyInAnyOrder(
                "col boards.time_tracking",
                "col boards.working_days",
                "col boards.board_timezone",
            )

        assertThat(newTablesOfV509())
            .containsExactlyInAnyOrder(
                "board_non_working_dates",
                "board_card_layout_fields",
                "board_detail_view_fields",
            )

        assertThat(addedByV509).anyMatch { it.startsWith("con boards.boards_time_tracking_allowed :: ") }
    }

    // ── ⑤ 분할기 비-공허 가드 — 0건이면 ①②③ 이 전부 공허해진다 ───────────────

    @Test
    fun `재실행 대상 문 목록이 V509 의 DDL 을 빠짐없이 담는다`() {
        // 구현 문법(IF NOT EXISTS · DO 블록)에 기대지 않는다 — 가드는 red 에서도 초록이어야
        // 「분할기가 멀쩡한데 마이그레이션이 안 멱등이다」를 가른다.
        assertThat(statements.filter { it.startsWith("CREATE TABLE") })
            .hasSize(3)
        assertThat(statements).anyMatch { it.startsWith("ALTER TABLE boards") && it.contains("time_tracking") }
        assertThat(statements.filter { it.contains("boards_time_tracking_allowed") })
            .hasSize(1)
    }

    /** V509 가 **새로 만든** 테이블. 스냅숏의 컬럼 항목에서 테이블 이름만 뽑아 차집합을 낸다. */
    private fun newTablesOfV509(): Set<String> = tablesIn(afterApply) - tablesIn(beforeV509)

    private fun tablesIn(snapshot: List<String>): Set<String> =
        snapshot
            .filter { it.startsWith("col ") }
            .map { it.removePrefix("col ").substringBefore('.') }
            .toSet()
}
