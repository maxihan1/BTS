// V510 검증 — working_days 원소 집합과 board_timezone 을 DB CHECK 로 막고 기존 데이터를 거부하지 않는지 잰다

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
import java.time.DateTimeException
import java.time.ZoneId
import java.util.UUID

/** V510 파일의 클래스패스 경로. Flyway 가 읽는 것과 **같은 파일**이어야 재실행 판정이 의미를 갖는다. */
private const val V510_RESOURCE = "/db/migration/agile-planning/V510__board_settings_value_checks.sql"

/** CHECK 제약 위반 SQLSTATE. 아무 예외로나 재면 테이블·칸 부재(42P01·42703)도 통과해 단언이 공허해진다. */
private const val CHECK_VIOLATION = "23514"

/** 타임존 이름을 PostgreSQL 이 못 읽을 때의 SQLSTATE — `invalid_parameter_value`. 실측값이다(아래 ⑤ 참조). */
private const val INVALID_PARAMETER = "22023"

/** 근무일 CHECK 이름. `<테이블>_<칸>_allowed` — 형제 `boards_board_type_allowed`(V505) 의 관용구다. */
private const val WORKING_DAYS_CONSTRAINT = "boards_working_days_allowed"

/** 타임존 CHECK 이름. 같은 관용구다. */
private const val TIMEZONE_CONSTRAINT = "boards_board_timezone_allowed"

/**
 * `V510__board_settings_value_checks.sql` 을 검증한다 (리뷰 CONCERN C4 · 부채 177).
 *
 * ## 왜 이 마이그레이션이 있나
 * `V509` 는 `time_tracking` 에 CHECK 를 건 근거를 스스로 이렇게 적었다 —
 * *「사전 검사는 사용자에게 이유를 주려고 있는 것이고 **마지막 방어선은 DB** 다」*(V509:66-67).
 * 그런데 같은 절이 낸 `working_days VARCHAR(3)[]` · `board_timezone VARCHAR(64)` 에는 제약이 없었고,
 * [com.bts.agileplanning.application.WorkingDaysSettingsService] 의 `WEEK_ORDER` 가 **유일한 방어선**이라고
 * 자기 KDoc 에 적고 있었다. 읽는 쪽은 그 단일 방어선을 신뢰한다 —
 * `SprintBurndownService` 가 해석 불가 요일 키를 `mapNotNull` 로 **조용히 버리고**(x축이 소리 없이 좁아진다)
 * `ZoneId.of(timezone)` 은 무방비다(비-IANA 문자열 한 행이면 그 보드의 번다운 API 가 영구 500).
 *
 * ## 이 클래스가 지는 판정 6축
 *
 * | 축 | 무엇 | 왜 |
 * |---|---|---|
 * | ① **기존 데이터 보존** | V510 **이전에** 있던 행(NULL · `{}` · 정상값)이 그대로 살아남는다 | CHECK 는 위반 행 하나에도 마이그레이션을 죽인다 |
 * | ② 근무일 위반 거부 | 소문자 · 미지 키 · **배열 원소 null** 이 23514 로 죽는다 | 서비스 검증의 마지막 방어선 |
 * | ③ 근무일 대조군 | 7종 전부 · `{}` · NULL 이 들어간다 | ② 만 두면 전부 죽이는 제약도 통과한다 |
 * | ④ 타임존 거부·대조군 | `Foo/Bar` · 빈 문자열은 죽고 `Asia/Seoul` · `UTC` · NULL 은 산다 | 영구 500 의 입구를 좁힌다 |
 * | ⑤ **경계 명시** | 이 CHECK 는 **PG 의 tz 이름 집합**이지 Java `ZoneId` 집합이 아니다 | 남은 구멍을 기계가 고정한다 |
 * | ⑥ 재실행 안전 | 파일을 손으로 다시 태워도 죽지 않는다 | 부채 161 · V509 와 같은 규율 |
 *
 * ## ★★ ① 이 이 클래스에서 가장 중요하다 — 그리고 ①' 가 그 짝이다
 * CHECK 를 거는 `ALTER TABLE` 은 **기존 행을 전부 검사**한다. 위반 행이 하나라도 있으면
 * 마이그레이션 자체가 실패하고 그 뒤 문이 전부 적용되지 않는다. ① 은 「정상 데이터는 통과한다」를,
 * ①' 는 **「위반 행이 있으면 실제로 죽는다」**를 잰다 — 둘을 함께 두어야 ① 이 공허해지지 않는다.
 *
 * ★그래서 이 PR 의 안전 근거는 **「V509 가 아직 배포되지 않았다」**이다. 두 칸을 낸 것이 같은 PR 의
 * V509 이므로 이 칸을 쓴 경로는 `WorkingDaysSettingsService` 하나뿐이고, 그 서비스는 `WEEK_ORDER` 와
 * `ZoneId.getAvailableZoneIds()` 로 이미 좁혀 놓은 값만 쓴다 — CHECK 보다 **좁은** 집합이다.
 * 확인 방법(2026-09-06 실측).
 * ```
 * git cat-file -e origin/main:backend/.../V509__board_settings_tabs.sql   # → 없음(미배포)
 * ```
 * V509 가 릴리스된 뒤였다면 이 파일은 `NOT VALID` + 별도 백필을 택했어야 한다.
 *
 * ## ★ ⑤ 가 재는 것 — 남은 구멍을 숨기지 않는다
 * IANA 목록을 CHECK 에 나열할 수는 없고(부분 목록은 곧 썩는다) `pg_timezone_names` 는 서브쿼리라
 * CHECK 에 못 쓴다. 그래서 이 파일은 **PostgreSQL 자신의 tz 데이터베이스**를 `timezone(text, timestamptz)`
 * (IMMUTABLE — 실측으로 확인) 로 두드려 판정한다. 그 집합은 Java `ZoneId` 집합과 **다른 목록**이라
 * POSIX 표기(`ABC5` 등)가 DB 를 통과하고 Java 에서 죽는다. ⑤ 는 그 차집합을 **단언으로 고정**한다 —
 * 산문으로만 적으면 다음 사람이 「DB 가 막아 준다」로 읽고 읽는 쪽 폴백을 지운다.
 *
 * ## 왜 전용 컨테이너 + 3단 부팅인가
 * 공유 컨테이너([com.bts.agileplanning.AgilePlanningTestcontainersConfig])는 무조건 최신까지 밀어버려
 * 「V510 **이전에** 심은 행」을 만들 수 없다. 형제 [BoardSettingsMigrationTest] 와 같은 이유로
 * `@Container` 로 전용 컨테이너를 띄우고 `target("509")` → 픽스처 → `target(null)` 로 나눈다.
 */
@Testcontainers
class BoardSettingsValueCheckMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_agileplanning_value_check_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /** V510 **적용 전에** 심은 보드들. ① 기존 데이터 보존의 기준선이다. */
        private lateinit var legacyBoards: Map<String, UUID>

        /** V510 파일을 통째로 다시 태운 결과. null 이면 성공이다(⑥). */
        private var replayFailure: String? = null

        /** 위반 행이 있는 상태에서 V510 을 태운 결과. ①' — 여기서 죽어야 ① 이 공허하지 않다. */
        private var failureWithViolatingRow: String? = null

        /** 위반 행을 치운 뒤 다시 태운 결과. ①' 의 대조군 — 죽는 이유가 그 행이었음을 가른다. */
        private var failureAfterCleanup: String? = null

        private fun flyway(target: String?) =
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/agile-planning")
                .let { if (target == null) it else it.target(target) }
                .load()

        /**
         * V509 까지 올린 뒤 **정상 데이터를 심고** 나머지 체인(V510)을 태운다.
         *
         * 픽스처를 V509 **뒤**에 심어야 「V510 이전부터 있던 행」이 만들어진다. 전체를 먼저 밀고
         * 심으면 ① 이 잴 것이 없어진다 — 그때는 CHECK 가 이미 걸린 뒤라 애초에 위반 행을 못 심는다.
         */
        @BeforeAll
        @JvmStatic
        fun migrateWithSeedBeforeV510() {
            flyway("509").migrate()
            legacyBoards =
                conn().use { c ->
                    mapOf(
                        // 설정을 한 번도 만지지 않은 보드 — 두 칸이 NULL 이다(스펙 R6 의 「미설정」).
                        "untouched" to seedBoard(c, "UNTOUCHED", null, null),
                        // 사용자가 명시적으로 0개를 저장한 상태. NULL 과 **다른 값**이라 뭉개면 안 된다.
                        "empty" to seedBoard(c, "EMPTYDAYS", arrayOf(), null),
                        "configured" to seedBoard(c, "CONFIGURED", arrayOf("MON", "TUE"), "Asia/Seoul"),
                    )
                }

            flyway(null).migrate()

            val script = readV510()
            replayFailure = runScript(script)

            // ①' — 위반 행을 만들어 놓고 같은 파일을 태운다. 제약을 먼저 떼야 위반 행을 심을 수 있다.
            failureWithViolatingRow = measureViolatingRowFailure(script)
            failureAfterCleanup = runScript(script)
        }

        /**
         * 제약을 떼고 위반 행을 심은 뒤 V510 을 다시 태운 결과를 잰다.
         *
         * ★ 심은 행은 **반드시 되돌린다** — 남겨 두면 뒤이은 [failureAfterCleanup] 대조군이
         * 같은 이유로 죽어 두 축이 서로를 가린다.
         */
        private fun measureViolatingRowFailure(script: String): String? {
            val violator = conn().use { c -> seedBoard(c, "VIOLATOR", null, null) }
            exec("ALTER TABLE boards DROP CONSTRAINT $WORKING_DAYS_CONSTRAINT")
            exec("ALTER TABLE boards DROP CONSTRAINT $TIMEZONE_CONSTRAINT")
            // ★ 3글자다. `MONDAY` 로 심으면 VARCHAR(3) 가 먼저 22001 로 거부해 **CHECK 를 재지 못한다**.
            setWorkingDaysOn(violator, arrayOf("ZZZ"))

            val failure = runScript(script)

            setWorkingDaysOn(violator, null)
            return failure
        }

        /** 위반 행을 심고 되돌리는 전용 쓰기. 제약이 떨어진 창에서만 쓴다. */
        private fun setWorkingDaysOn(
            boardId: UUID,
            days: Array<String>?,
        ) {
            conn().use { c ->
                c.prepareStatement("UPDATE boards SET working_days = ? WHERE id = ?").use { ps ->
                    ps.setArray(1, days?.let { c.createArrayOf("varchar", it) })
                    ps.setObject(2, boardId)
                    ps.executeUpdate()
                }
            }
        }

        fun conn(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

        private fun readV510(): String =
            checkNotNull(BoardSettingsValueCheckMigrationTest::class.java.getResourceAsStream(V510_RESOURCE)) {
                "$V510_RESOURCE 를 클래스패스에서 찾지 못했다 — 재실행 판정이 잴 대상이 없다"
            }.use { it.readBytes().toString(Charsets.UTF_8) }

        /**
         * 파일 전문을 **한 번에** 실행하고 SQLSTATE 를 돌려준다. 성공이면 null 이다.
         *
         * 문 단위로 쪼개지 않는 것은 이 파일이 DO 블록 둘뿐이라서다 — 쪼개는 파서를 두면
         * 파서가 못 읽은 문이 조용히 0건이 되어 판정이 공허해진다(형제 파일이 겪은 자리).
         */
        private fun runScript(script: String): String? =
            try {
                conn().use { c ->
                    c.autoCommit = true
                    c.createStatement().use { st -> st.execute(script) }
                }
                null
            } catch (e: SQLException) {
                "${e.sqlState}: ${e.message?.lineSequence()?.firstOrNull()}"
            }

        private fun exec(sql: String) {
            conn().use { c -> c.createStatement().use { st -> st.execute(sql) } }
        }

        private fun seedBoard(
            c: Connection,
            projectKey: String,
            workingDays: Array<String>?,
            timezone: String?,
        ): UUID {
            val id = UUID.randomUUID()
            c.prepareStatement(
                "INSERT INTO boards (id, project_key, name, working_days, board_timezone) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, projectKey)
                ps.setString(3, "V510 이전부터 있던 보드")
                ps.setArray(4, workingDays?.let { c.createArrayOf("varchar", it) })
                ps.setString(5, timezone)
                ps.executeUpdate()
            }
            return id
        }
    }

    // ── 헬퍼 — 전부 prepared statement (SQL 문자열 결합 금지) ────────────────────

    /** [block] 이 던진 SQLSTATE. 죽지 **않으면** null 이라 「통과해 버렸다」가 그대로 드러난다. */
    private fun sqlStateOf(block: () -> Unit): String? =
        try {
            block()
            null
        } catch (e: SQLException) {
            e.sqlState
        }

    private fun setWorkingDays(
        boardId: UUID,
        days: Array<String?>?,
    ) {
        conn().use { c ->
            c.prepareStatement("UPDATE boards SET working_days = ? WHERE id = ?").use { ps ->
                ps.setArray(1, days?.let { c.createArrayOf("varchar", it) })
                ps.setObject(2, boardId)
                ps.executeUpdate()
            }
        }
    }

    private fun setTimezone(
        boardId: UUID,
        timezone: String?,
    ) {
        conn().use { c ->
            c.prepareStatement("UPDATE boards SET board_timezone = ? WHERE id = ?").use { ps ->
                ps.setString(1, timezone)
                ps.setObject(2, boardId)
                ps.executeUpdate()
            }
        }
    }

    @Suppress("NestedBlockDepth")
    private fun readSettings(boardId: UUID): Pair<List<String?>?, String?> =
        conn().use { c ->
            c.prepareStatement("SELECT working_days, board_timezone FROM boards WHERE id = ?").use { ps ->
                ps.setObject(1, boardId)
                ps.executeQuery().use { rs ->
                    rs.next()
                    val raw = rs.getArray("working_days")

                    @Suppress("UNCHECKED_CAST")
                    val days = (raw?.array as Array<String?>?)?.toList()
                    days to rs.getString("board_timezone")
                }
            }
        }

    /** 테이블에 걸린 CHECK 제약 이름 목록. */
    @Suppress("NestedBlockDepth")
    private fun checkConstraintNames(table: String): List<String> =
        conn().use { c ->
            c.prepareStatement(
                """
                SELECT c.conname
                FROM pg_constraint c
                JOIN pg_class t ON c.conrelid = t.oid
                JOIN pg_namespace n ON t.relnamespace = n.oid
                WHERE n.nspname = 'public' AND t.relname = ? AND c.contype = 'c'
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, table)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<String>()
                    while (rs.next()) out.add(rs.getString(1))
                    out
                }
            }
        }

    /** 판정용 임시 보드. 축마다 새로 심어 앞 축이 남긴 값에 얹히지 않게 한다. */
    private fun freshBoard(): UUID {
        val key = "T${UUID.randomUUID().toString().take(6)}"
        return conn().use { seedBoard(it, key, null, null) }
    }

    // ── ① 기존 데이터 보존 ──────────────────────────────────────────────────────

    @Test
    fun `V510 이전부터 있던 미설정 보드는 두 칸이 NULL 로 남는다`() {
        val (days, timezone) = readSettings(requireNotNull(legacyBoards["untouched"]))

        assertThat(days).isNull()
        assertThat(timezone).isNull()
    }

    @Test
    fun `V510 이전부터 있던 빈 배열과 정상값이 그대로 살아남는다`() {
        // 빈 배열은 NULL 과 **다른 값**이다(스펙 R6). CHECK 가 `{}` 를 거부하면 이 행이 마이그레이션을 죽인다.
        assertThat(readSettings(requireNotNull(legacyBoards["empty"])).first).isEmpty()

        val (days, timezone) = readSettings(requireNotNull(legacyBoards["configured"]))
        assertThat(days).containsExactly("MON", "TUE")
        assertThat(timezone).isEqualTo("Asia/Seoul")
    }

    // ── ①' 위반 행이 있으면 마이그레이션은 죽는다 (① 의 짝) ────────────────────

    @Test
    fun `위반 행이 있으면 V510 은 실패하고 그 행을 치우면 성공한다`() {
        // ★ 이 축이 없으면 ① 은 「어차피 아무것도 안 막는 제약」에도 초록이다.
        assertThat(failureWithViolatingRow)
            .describedAs("위반 행이 남아 있으면 CHECK 를 거는 ALTER 가 죽어야 한다")
            .isNotNull()
            .asString()
            .startsWith(CHECK_VIOLATION)

        assertThat(failureAfterCleanup)
            .describedAs("위반 행을 치우면 같은 파일이 성공해야 한다 — 죽은 이유가 그 행임을 가른다")
            .isNull()
    }

    // ── ② 근무일 위반 거부 ──────────────────────────────────────────────────────

    @Test
    fun `근무일에 미지원 키를 넣으면 CHECK 위반으로 죽는다`() {
        val board = freshBoard()

        assertThat(sqlStateOf { setWorkingDays(board, arrayOf("XXX")) }).isEqualTo(CHECK_VIOLATION)
    }

    @Test
    fun `근무일 키는 대소문자를 가린다`() {
        // 서비스도 대소문자를 접지 않는다(WorkingDaysSettingsService KDoc). 두 층의 집합이 같아야
        // 「서비스는 400 인데 DB 는 통과」 같은 어긋남이 생기지 않는다.
        val board = freshBoard()

        assertThat(sqlStateOf { setWorkingDays(board, arrayOf("mon")) }).isEqualTo(CHECK_VIOLATION)
    }

    @Test
    fun `배열 원소의 null 도 CHECK 위반이다`() {
        // 배열 칸은 NOT NULL 로 원소 null 을 막지 못한다 — 원소 null 은 읽는 쪽에서 조용히 사라진다.
        val board = freshBoard()

        assertThat(sqlStateOf { setWorkingDays(board, arrayOf("MON", null)) }).isEqualTo(CHECK_VIOLATION)
    }

    // ── ③ 근무일 대조군 ────────────────────────────────────────────────────────

    @Test
    fun `요일 7종과 빈 배열과 NULL 은 그대로 들어간다`() {
        // ★ ② 의 대조군. 「전부 죽이는 제약」도 부정 단언만으로는 통과한다.
        val board = freshBoard()
        val week = arrayOf<String?>("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

        setWorkingDays(board, week)
        assertThat(readSettings(board).first).containsExactly(*week)

        setWorkingDays(board, arrayOf())
        assertThat(readSettings(board).first).isEmpty()

        setWorkingDays(board, null)
        assertThat(readSettings(board).first).isNull()
    }

    // ── ④ 타임존 거부·대조군 ────────────────────────────────────────────────────

    @Test
    fun `타임존이 아닌 문자열은 저장되지 않는다`() {
        val board = freshBoard()

        assertThat(sqlStateOf { setTimezone(board, "Foo/Bar") }).isEqualTo(INVALID_PARAMETER)
        assertThat(sqlStateOf { setTimezone(board, "") }).isEqualTo(INVALID_PARAMETER)
        assertThat(readSettings(board).second).isNull()
    }

    @Test
    fun `IANA 타임존과 NULL 은 그대로 들어간다`() {
        // ★ 위 부정 단언의 대조군. UTC 는 스펙 E7 의 「미설정 = UTC」와 같은 값이라 반드시 살아야 한다.
        val board = freshBoard()

        listOf("Asia/Seoul", "UTC", "America/New_York").forEach { zone ->
            setTimezone(board, zone)
            assertThat(readSettings(board).second).isEqualTo(zone)
        }

        setTimezone(board, null)
        assertThat(readSettings(board).second).isNull()
    }

    // ── ⑤ 경계 명시 — DB 의 집합 ≠ Java ZoneId 집합 ─────────────────────────────

    @Test
    fun `POSIX 표기는 DB 를 통과하고 Java ZoneId 에서 죽는다 — 읽는 쪽 폴백이 여전히 필요하다`() {
        // ★ 이 단언은 결함을 고정하는 것이 아니라 **남은 구멍의 위치**를 고정한다.
        // CHECK 가 보는 것은 PostgreSQL 의 tz 이름 집합이고 그것은 Java 의 집합과 다른 목록이다.
        // 여기가 red 가 되면(=DB 가 더 좁아졌거나 Java 가 넓어졌다) 이 문장을 다시 써야 한다.
        val board = freshBoard()

        assertThat(sqlStateOf { setTimezone(board, "ABC5") })
            .describedAs("PostgreSQL 은 POSIX 표기를 타임존으로 받아들인다")
            .isNull()
        assertThat(runCatching { ZoneId.of("ABC5") }.exceptionOrNull())
            .describedAs("같은 값이 Java 에서는 DateTimeException 이다 — 번다운 읽기 경로가 여기서 죽는다")
            .isInstanceOf(DateTimeException::class.java)

        setTimezone(board, null)
    }

    // ── ⑥ 재실행 안전 · 제약 이름 ───────────────────────────────────────────────

    @Test
    fun `V510 파일을 손으로 다시 태워도 죽지 않는다`() {
        // ADD CONSTRAINT 에는 IF NOT EXISTS 문법이 없다 — pg_constraint 를 보는 DO 블록이 그 처방이다.
        assertThat(replayFailure).isNull()
    }

    @Test
    fun `제약 이름이 형제 boards_board_type_allowed 의 관용구를 따른다`() {
        // 이름이 곧 계약이다 — 멱등 DO 블록이 pg_constraint 를 이 이름으로 찾는다.
        assertThat(checkConstraintNames("boards"))
            .contains(WORKING_DAYS_CONSTRAINT, TIMEZONE_CONSTRAINT, "boards_board_type_allowed")
    }
}
